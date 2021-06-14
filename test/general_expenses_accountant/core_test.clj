(ns general-expenses-accountant.core-test
  (:refer-clojure :exclude [reduce])
  (:require [clojure.test :refer [use-fixtures deftest testing is]]
            [clojure.core.async :refer [go chan >! <!! close! reduce]]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.zip :as zip]

            [taoensso.encore :as encore :refer [defalias]]
            [mount.core :as mount]

            [general-expenses-accountant.core :as core
             :refer [bot-api op-succeed get-datetime-in-tg-format]]
            [general-expenses-accountant.domain.chat :as chats]
            [general-expenses-accountant.domain.tlog :as tlogs]

            [general-expenses-accountant.tg-client :as tg-client]
            [general-expenses-accountant.utils :as utils])
  (:import [java.util.concurrent.atomic AtomicInteger]))

;; TODO: Learn how to properly mock 'clj-http' requests?

; Aliases (of required private "getter" fns)

(defalias get-chat-data core/get-chat-data)
(defalias get-members-count core/get-members-count)
(defalias get-chat-state core/get-chat-state)
(defalias get-bot-msg-id core/get-bot-msg-id)
(defalias get-bot-msg-state core/get-bot-msg-state)
(defalias get-personal-account core/get-personal-account)
(defalias find-personal-account-by-name core/find-personal-account-by-name)

; Shared State

(def ^:private bot-user
  {:id 1,
   :is_bot true,
   :first_name "Bot #1",
   :username "number_one_bot"})

(def ^:private *bot-data (atom {}))

(defn- start-required-states [f]
  (-> (mount/only #{;#'config/loader ;; TODO: Use another config for tests.
                    #'general-expenses-accountant.core/bot-user
                    #'general-expenses-accountant.core/*bot-data})
      (mount/swap {#'general-expenses-accountant.core/bot-user bot-user
                   #'general-expenses-accountant.core/*bot-data *bot-data})
      mount/start)
  (f))

(use-fixtures :once start-required-states)

(defn- reset-bot-data-afterwards [f]
  (let [bot-data @*bot-data
        f-result (f)]
    (reset! *bot-data bot-data)
    f-result))

(def ^{:private true :tag 'AtomicInteger} latest-chat-id (AtomicInteger.))
(def ^{:private true :tag 'AtomicInteger} latest-user-id (AtomicInteger.))
(def ^{:private true :tag 'AtomicInteger} latest-update-id (AtomicInteger.))
(def ^{:private true :tag 'AtomicInteger} latest-message-id (AtomicInteger.))
(def ^{:private true :tag 'AtomicInteger} latest-callback-query-id (AtomicInteger.))

; Generators

(defn- generate-chat-id
  []
  (.decrementAndGet latest-chat-id))

(defn- generate-user-id
  []
  (.incrementAndGet latest-user-id))

(def ^:private default-name-str-length 5)
(defn- generate-name-str
  ([]
   (generate-name-str default-name-str-length))
  ([n-or-prefix]
   (cond
     (int? n-or-prefix) (generate-name-str n-or-prefix nil)
     (string? n-or-prefix) (generate-name-str default-name-str-length n-or-prefix)))
  ([n prefix]
   {:pre [(pos-int? n)]}
   (apply str prefix (map char (repeatedly n #(rand-nth (range (int \A) (inc (int \Z)))))))))

(defn- generate-update-id
  []
  (.incrementAndGet latest-update-id))

(defn- generate-message-id
  []
  (.incrementAndGet latest-message-id))

(defn- generate-callback-query-id
  []
  (.incrementAndGet latest-callback-query-id))

; Aux Fns

(defn- to-tg-response
  "Mimics a successful Telegram Bot API response"
  [ids response opts]
  ;; TODO: Implement other cases: inline queries, non-empty callbacks?
  (let [result (case (:type response)
                 :text (let [drop-nil-vals #(into {} (filter (comp some? val) %))
                             chat (-> (get @*bot-data (:chat-id ids))
                                      (select-keys [:id :title :type])
                                      (update :type name))]
                         (drop-nil-vals
                           (if (true? (:replace? opts))
                             {:message_id (:msg-id ids)
                              :from bot-user
                              :chat chat
                              :date (get-datetime-in-tg-format) ;; don't remember
                              :edit_date (get-datetime-in-tg-format)
                              :text (:text response)
                              :entities (:entities response)
                              :reply_markup (-> response :options :reply_markup)}
                             {:message_id (generate-message-id)
                              :from bot-user
                              :chat chat
                              :date (get-datetime-in-tg-format)
                              :text (:text response)
                              :entities (:entities response)
                              :reply_markup (-> response :options :reply_markup)})))
                 :callback true)]
    {:ok true
     :result result}))

(defn- build-update
  [upd-type upd-data & _params]
  {:pre [(keyword? upd-type) (map? upd-data)]}
  {:update_id (generate-update-id)
   upd-type upd-data})

(defn- get-inline-kbd-btn
  [res i j]
  (-> res :reply_markup :inline_keyboard (get i) (get j)))

(defn- get-inline-kbd-row
  [res n]
  (-> res :reply_markup :inline_keyboard (get n)))

(defn- get-inline-kbd-col
  [res n]
  (->> res :reply_markup :inline_keyboard (map #(get % n))))

(defn- consists-exactly-of?
  [res key text-vals]
  ;; NB: Asserts a linear response structure, which is fine for now.
  (or (= text-vals (mapv key (get-inline-kbd-row res 0)))
      (= text-vals (mapv key (get-inline-kbd-col res 0)))))

(defn- contains-all?
  [res key text-vals]
  ;; NB: Asserts a linear response structure, which is fine for now.
  (or (every? (set (map key (get-inline-kbd-row res 0))) text-vals)
      (every? (set (map key (get-inline-kbd-col res 0))) text-vals)))

(defn- not-contains?
  [res key text-vals]
  ;; NB: Asserts a linear response structure, which is fine for now.
  (and (not-any? (set (map key (get-inline-kbd-row res 0))) text-vals)
       (not-any? (set (map key (get-inline-kbd-col res 0))) text-vals)))

; Mocks > DB

(def ^:private mock-db-queries
  {#'chats/select-all #(vector)
   #'chats/create! identity
   #'chats/update! identity
   #'tlogs/select-by-chat-id (fn [chat-id] {:id chat-id})
   #'tlogs/create! identity})

(defmacro with-mock-db
  ([test-func]
   `(with-mock-db {} ~test-func))
  ([mock-fns test-func]
   `(with-redefs-fn
      (merge mock-db-queries ~mock-fns)
      ~test-func)))

; Mocks > Bot API

(def ^:private collect-responses (constantly []))

(defn- do-responses-match?
  [all-resps & resp-assert-preds]
  (loop [resps all-resps
         preds resp-assert-preds]
    (if (empty? preds)
      true ;; some resps match all predicates, finish!
      (let [pred (first preds)
            resp (filter pred resps)]
        (if (empty? resp)
          false ;; none of resps match some predicate!
          (recur (if (< 1 (count resp))
                   resps ;; loose matches are retained
                   (remove #(= (first resp) %) resps))
                 (rest preds)))))))

(defmacro with-mock-send
  ([test-func]
   `(with-mock-send {} ~test-func))
  ([mock-fns test-func]
   `(let [resp-chan# (chan)
          mock-send# (fn [_# ids# response# opts#]
                       (let [tg-response# (to-tg-response ids# response# opts#)]
                         (go (>! resp-chan# (:result tg-response#)))
                         tg-response#))
          responses# (memoize (fn []
                                (Thread/sleep 50)
                                (close! resp-chan#)
                                (<!! (reduce conj [] resp-chan#))))]
      (with-mock-db
        (merge {#'core/send! mock-send#
                #'collect-responses responses#}
               ~mock-fns)
        ~test-func))))

; Assertions

(defn- valid-name-request-msg?
  [res chat-id]
  (and (= chat-id (-> res :chat :id))
       (= (get-bot-msg-id chat-id :name-request-msg-id)
          (:message_id res))))

(defn- valid-settings-msg?
  ([res chat-id]
   (valid-settings-msg? res chat-id nil))
  ([res chat-id settings-msg-id]
   (and (= chat-id (-> res :chat :id))
        (or (nil? settings-msg-id) ;; don't check
            (= settings-msg-id (:message_id res)))
        (some? (:reply_markup res))
        (consists-exactly-of? res :callback_data
                              ["<accounts>" "<expense_items>" "<shares>"]))))

(defn- valid-accounts-mgmt-msg?
  [res chat-id & acc-names]
  (and (= chat-id (-> res :chat :id))
       (or (empty? acc-names)
           (every? identity ;; which, basically, does 'and'
                   (map #(str/includes? (:text res) %) acc-names)))
       (some? (:reply_markup res))
       (contains-all? res :callback_data
                      ["<accounts/create>" "<accounts/rename>"
                       "<accounts/revoke>" "<accounts/reinstate>"])))

(defn- valid-new-account-name-request-msg?
  [res chat-id user-name user-id]
  (and (= chat-id (-> res :chat :id))
       (some? (:reply_markup res))
       (-> res :reply_markup :force_reply)
       (-> res :reply_markup :selective)
       (str/includes? (:text res) user-name)
       (= (get-bot-msg-id chat-id [:to-user user-id :request-acc-name-msg-id])
          (:message_id res))))

(defn- valid-account-rename-request-msg?
  [res chat-id user-name user-id account-to-rename-old-name]
  (and (= chat-id (-> res :chat :id))
       (some? (:reply_markup res))
       (-> res :reply_markup :force_reply)
       (-> res :reply_markup :selective)
       (str/includes? (:text res) user-name)
       (str/includes? (:text res) account-to-rename-old-name)
       (= (get-bot-msg-id chat-id [:to-user user-id :request-rename-msg-id])
          (:message_id res))))

; Test Cases

(def start-new-chat-test-group
  {:type :test/group
   :name "1 Start a new chat"
   :test [{:type :test/case
           :name "1-1 Create chat with bot"
           :tags [:new-group-chat :single-user]
           :give :name-request-msg

           :update {:mock-fns {#'tg-client/get-chat-members-count (constantly 2)}
                    :type :my_chat_member
                    :data (fn [ctx]
                            {:chat {:id (:chat-id ctx) :title (:chat-title ctx) :type "group"}
                             :from {:id (:user-id ctx) :first_name (:user-name ctx) :language_code "ru"}
                             :date (get-datetime-in-tg-format)
                             :old_chat_member {:user bot-user :status "left"}
                             :new_chat_member {:user bot-user :status "member"}})}
           :checks {:result op-succeed
                    :chat-data {:members-count 2
                                :chat-state :waiting}
                    :responses {:total 2
                                :assert-preds (fn [ctx]
                                                [(fn [res]
                                                   (and (= (:chat-id ctx) (-> res :chat :id))
                                                        (str/includes? (:text res) (:user-name ctx))))
                                                 (fn [res]
                                                   (valid-name-request-msg? res (:chat-id ctx)))])}}
           :return-fn (fn [ctx responses]
                        (let [resp-pred #(valid-name-request-msg? % (:chat-id ctx))]
                          (first (filter resp-pred responses))))}

          ;; TODO: Add a TC that checks that other interactions are ineffectual at this point.

          {:type :test/case
           :name "1-2 Reply to the name request message"
           :tags [:new-group-chat :single-user]
           :take :name-request-msg
           :give :settings-msg

           :update {:mock-fns {#'core/can-write-to-user? (constantly true)} ;; as if user has already started some chat
                    :type :message
                    :data (fn [ctx]
                            {:message_id (generate-message-id)
                             :chat {:id (:chat-id ctx) :title (:chat-title ctx) :type "group"}
                             :from {:id (:user-id ctx) :first_name (:user-name ctx) :language_code "ru"}
                             :date (get-datetime-in-tg-format)
                             :reply_to_message (-> ctx :deps :name-request-msg)
                             :text (:user-personal-account-name ctx)})}
           :checks {:result op-succeed
                    :chat-data {:members-count 2
                                :chat-state :ready
                                :accounts (fn [ctx]
                                            [{:name (:user-personal-account-name ctx)}])}
                    :responses {:total 3
                                :assert-preds (fn [ctx]
                                                [(fn [res]
                                                   (and (= (:user-id ctx) (-> res :chat :id))
                                                        (str/includes? (:text res) (:chat-title ctx))))
                                                 (fn [res]
                                                   (and (= (:chat-id ctx) (-> res :chat :id))
                                                        (some? (:reply_markup res))
                                                        (= "https://t.me/number_one_bot"
                                                           (:url (get-inline-kbd-btn res 0 0)))))
                                                 (fn [res]
                                                   (and (valid-settings-msg? res (:chat-id ctx))
                                                        (= [:settings :initial]
                                                           (get-bot-msg-state (get-chat-data (:chat-id ctx))
                                                                              (:message_id res)))))])}}
           :return-fn (fn [ctx responses]
                        (let [resp-pred #(valid-settings-msg? % (:chat-id ctx))]
                          (first (filter resp-pred responses))))}]})

(def enter-the-accounts-menu
  {:type :test/case
   :name "Enter the 'Accounts' menu"
   :tags [:menu-navigation]
   :bind {:callback-query-id (generate-callback-query-id)}
   :take :settings-msg
   :give :accounts-mgmt-msg

   :update {:type :callback_query
            :data (fn [ctx]
                    {:id (:callback-query-id ctx)
                     :chat {:id (:chat-id ctx) :title (:chat-title ctx) :type "group"}
                     :from {:id (:user-id ctx) :first_name (:user-name ctx) :language_code "ru"}
                     :message (-> ctx :deps :settings-msg)
                     :data "<accounts>"})}
   :checks {:result (fn [ctx]
                      {:method "answerCallbackQuery"
                       :callback_query_id (:callback-query-id ctx)})
            :chat-data {:chat-state :ready
                        :bot-messages (fn [ctx]
                                        [{:msg-id (-> ctx :deps :settings-msg :message_id)
                                          :msg-state [:settings :accounts-mgmt]}])}
            :responses {:total 1
                        :assert-preds (fn [ctx]
                                        [(fn [res]
                                           (valid-accounts-mgmt-msg? res (:chat-id ctx)
                                                                     (:user-personal-account-name ctx)))])}}
   :return-fn (fn [_ responses]
                (first responses))})

(def exit-the-accounts-menu
  {:type :test/case
   :name "Exit the 'Accounts' menu"
   :tags [:menu-navigation]
   :bind {:callback-query-id (generate-callback-query-id)}
   :take :accounts-mgmt-msg

   :update {:type :callback_query
            :data (fn [ctx]
                    {:id (:callback-query-id ctx)
                     :chat {:id (:chat-id ctx) :title (:chat-title ctx) :type "group"}
                     :from {:id (:user-id ctx) :first_name (:user-name ctx) :language_code "ru"}
                     :message (-> ctx :deps :accounts-mgmt-msg)
                     :data "<back>"})}
   :checks {:result (fn [ctx]
                      {:method "answerCallbackQuery"
                       :callback_query_id (:callback-query-id ctx)})
            :chat-data {:chat-state :ready
                        :bot-messages (fn [ctx]
                                        [{:msg-id (-> ctx :deps :settings-msg :message_id)
                                          :msg-state [:settings :initial]}])}
            :responses {:total 1
                        :assert-preds (fn [ctx]
                                        [(fn [res]
                                           (valid-settings-msg? res (:chat-id ctx)
                                                                (-> ctx :deps :settings-msg :message_id)))])}}})

(def create-account-test-group
  {:type :test/group
   :name "2-1-1 Create a new account"
   :test [{:type :test/case
           :name "Should prompt the user to select the type of a new account"
           :tags [:create-account]
           :bind {:callback-query-id (generate-callback-query-id)}
           :take :accounts-mgmt-msg
           :give :create-account-msg

           :update {:type :callback_query
                    :data (fn [ctx]
                            {:id (:callback-query-id ctx)
                             :chat {:id (:chat-id ctx) :title (:chat-title ctx) :type "group"}
                             :from {:id (:user-id ctx) :first_name (:user-name ctx) :language_code "ru"}
                             :message (-> ctx :deps :accounts-mgmt-msg)
                             :data "<accounts/create>"})}
           :checks {:result (fn [ctx]
                              {:method "answerCallbackQuery"
                               :callback_query_id (:callback-query-id ctx)})
                    :chat-data {:chat-state :ready
                                :bot-messages (fn [ctx]
                                                [{:msg-id (-> ctx :deps :settings-msg :message_id)
                                                  :msg-state [:settings :account-type-selection]}])}
                    :responses {:total 1
                                :assert-preds (fn [ctx]
                                                [(fn [res]
                                                   (and (= (:chat-id ctx) (-> res :chat :id))
                                                        (some? (:reply_markup res))
                                                        (contains-all? res :callback_data
                                                                       ["at::group" "at::personal"])))])}}
           :return-fn (fn [_ responses]
                        (first responses))}

          {:type :test/case
           :name "Should restore the settings message & prompt the user for an account name"
           :tags [:create-account]
           :bind {:callback-query-id (generate-callback-query-id)}
           :take :create-account-msg
           :give [:settings-msg :new-account-name-request-msg]

           :update {:type :callback_query
                    :data (fn [ctx]
                            {:id (:callback-query-id ctx)
                             :chat {:id (:chat-id ctx) :title (:chat-title ctx) :type "group"}
                             :from {:id (:user-id ctx) :first_name (:user-name ctx) :language_code "ru"}
                             :message (-> ctx :deps :create-account-msg)
                             :data (:account-type ctx)})}
           :checks {:result (fn [ctx]
                              {:method "answerCallbackQuery"
                               :callback_query_id (:callback-query-id ctx)})
                    :chat-data {:chat-state :waiting
                                :bot-messages (fn [ctx]
                                                [{:msg-id (-> ctx :deps :settings-msg :message_id)
                                                  :msg-state [:settings :initial]}])}
                    :responses {:total 2
                                :assert-preds (fn [ctx]
                                                [(fn [res]
                                                   (valid-settings-msg? res (:chat-id ctx)
                                                                        (-> ctx :deps :settings-msg :message_id)))
                                                 (fn [res]
                                                   (valid-new-account-name-request-msg?
                                                     res (:chat-id ctx) (:user-name ctx) (:user-id ctx)))])}}
           :return-fn (fn [ctx responses]
                        (let [[res1 res2] responses]
                          ;; NB: Need to return responses in a proper order (see the ':give').
                          (if (valid-settings-msg? res1 (:chat-id ctx)) responses [res2 res1])))}

          ;; TODO: Add a TC that checks that other interactions are ineffectual at this point.

          {:type :test/case
           :name "Should create a new account of the selected type and with the specified name"
           :tags [:create-account]
           :take :new-account-name-request-msg
           :give :created-account

           :update {:type :message
                    :data (fn [ctx]
                            {:message_id (generate-message-id)
                             :chat {:id (:chat-id ctx) :title (:chat-title ctx) :type "group"}
                             :from {:id (:user-id ctx) :first_name (:user-name ctx) :language_code "ru"}
                             :date (get-datetime-in-tg-format)
                             :reply_to_message (-> ctx :deps :new-account-name-request-msg)
                             :text (:account-name ctx)})}
           :checks {:result op-succeed
                    :chat-data {:chat-state :ready
                                :accounts (fn [ctx]
                                            [{:name (:account-name ctx)}])}
                    :responses {:total 1
                                :assert-preds (fn [ctx]
                                                [(fn [res]
                                                   (= (:chat-id ctx) (-> res :chat :id)))])}}
           :return-fn (fn [ctx _]
                        (find-personal-account-by-name (get-chat-data (:chat-id ctx))
                                                       (:account-name ctx)))}]})

(def create-virtual-personal-account-test-group
  (assoc create-account-test-group
    :bind {:account-type "at::personal"
           :account-name :virtual-personal-account-name}))

(def rename-account-test-group
  {:type :test/group
   :name "2-1-2 Rename an account"
   :test [{:type :test/case
           :name "Should prompt the user to select an account to rename"
           :tags [:rename-account]
           :bind {:callback-query-id (generate-callback-query-id)}
           :take :accounts-mgmt-msg
           :give :account-to-rename-selection-msg

           :update {:type :callback_query
                    :data (fn [ctx]
                            {:id (:callback-query-id ctx)
                             :chat {:id (:chat-id ctx) :title (:chat-title ctx) :type "group"}
                             :from {:id (:user-id ctx) :first_name (:user-name ctx) :language_code "ru"}
                             :message (-> ctx :deps :accounts-mgmt-msg)
                             :data "<accounts/rename>"})}
           :checks {:result (fn [ctx]
                              {:method "answerCallbackQuery"
                               :callback_query_id (:callback-query-id ctx)})
                    :chat-data {:chat-state :ready
                                :bot-messages (fn [ctx]
                                                [{:msg-id (-> ctx :deps :settings-msg :message_id)
                                                  :msg-state [:settings :account-renaming]}])}
                    :responses {:total 1
                                :assert-preds (fn [ctx]
                                                [(fn [res]
                                                   (and (= (:chat-id ctx) (-> res :chat :id))
                                                        (some? (:reply_markup res))
                                                        (contains-all? res :text
                                                                       [(:account-to-rename-old-name ctx)])))])}}
           :return-fn (fn [_ responses]
                        (first responses))}

          {:type :test/case
           :name "Should restore the settings message & prompt the user for an account name"
           :tags [:rename-account]
           :bind {:callback-query-id (generate-callback-query-id)}
           :take :account-to-rename-selection-msg
           :give :account-rename-request-msg

           :update {:type :callback_query
                    :data (fn [ctx]
                            {:id (:callback-query-id ctx)
                             :chat {:id (:chat-id ctx) :title (:chat-title ctx) :type "group"}
                             :from {:id (:user-id ctx) :first_name (:user-name ctx) :language_code "ru"}
                             :message (-> ctx :deps :account-to-rename-selection-msg)
                             :data (:account-to-rename-cd ctx)})}
           :checks {:result (fn [ctx]
                              {:method "answerCallbackQuery"
                               :callback_query_id (:callback-query-id ctx)})
                    :chat-data {:chat-state :waiting
                                :bot-messages (fn [ctx]
                                                [{:msg-id (-> ctx :deps :settings-msg :message_id)
                                                  :msg-state [:settings :initial]}])}
                    :responses {:total 2
                                :assert-preds (fn [ctx]
                                                [(fn [res]
                                                   (valid-settings-msg? res (:chat-id ctx)
                                                                        (-> ctx :deps :settings-msg :message_id)))
                                                 (fn [res]
                                                   (valid-account-rename-request-msg?
                                                     res (:chat-id ctx) (:user-name ctx) (:user-id ctx)
                                                     (:account-to-rename-old-name ctx)))])}}
           :return-fn (fn [ctx responses]
                        (let [resp-pred #(valid-account-rename-request-msg?
                                           % (:chat-id ctx) (:user-name ctx) (:user-id ctx)
                                           (:account-to-rename-old-name ctx))]
                          (first (filter resp-pred responses))))}

          ;; TODO: Add a TC that checks that other interactions are ineffectual at this point.

          {:type :test/case
           :name "Should update the selected account with the specified name"
           :tags [:rename-account]
           :take :account-rename-request-msg

           :update {:type :message
                    :data (fn [ctx]
                            {:message_id (generate-message-id)
                             :chat {:id (:chat-id ctx) :title (:chat-title ctx) :type "group"}
                             :from {:id (:user-id ctx) :first_name (:user-name ctx) :language_code "ru"}
                             :date (get-datetime-in-tg-format)
                             :reply_to_message (-> ctx :deps :account-rename-request-msg)
                             :text (:account-to-rename-new-name ctx)})}
           :checks {:result op-succeed
                    :chat-data {:chat-state :ready
                                :accounts (fn [ctx]
                                            [{:name (:account-to-rename-new-name ctx)}])}
                    :responses {:total 1
                                :assert-preds (fn [ctx]
                                                [(fn [res]
                                                   (= (:chat-id ctx) (-> res :chat :id)))])}}}]})

(def rename-user-personal-account-test-group
  (assoc rename-account-test-group
    :bind {:account-to-rename-cd :user-personal-account-cd
           :account-to-rename-old-name :user-personal-account-name
           :account-to-rename-new-name (generate-name-str "Acc/")}))

(def rename-virtual-personal-account-test-group
  (assoc rename-account-test-group
    :bind {:account-to-rename-cd :virtual-personal-account-cd
           :account-to-rename-old-name :virtual-personal-account-name
           :account-to-rename-new-name (generate-name-str "Acc/")}))

;; NB: To be run twice: 1. when there are no accs; 2. after revoking an acc.
(def no-eligible-accounts-for-revocation
  {:type :test/case
   :name "Should notify user when there's no eligible accounts for revocation"
   :tags [:revoke-account :notifications]
   :bind {:callback-query-id (generate-callback-query-id)}
   :take :accounts-mgmt-msg

   :update {:type :callback_query
            :data (fn [ctx]
                    {:id (:callback-query-id ctx)
                     :chat {:id (:chat-id ctx) :title (:chat-title ctx) :type "group"}
                     :from {:id (:user-id ctx) :first_name (:user-name ctx) :language_code "ru"}
                     :message (-> ctx :deps :accounts-mgmt-msg)
                     :data "<accounts/revoke>"})}
   :checks {:result (fn [ctx]
                      {:method "answerCallbackQuery"
                       :callback_query_id (:callback-query-id ctx)})
            :chat-data {:chat-state :ready}
            :responses {:total 1
                        :assert-preds [#(= true %)]}}})

(def revoke-account-test-group
  {:type :test/group
   :name "2-1-3 Revoke an account"
   :test [{:type :test/case
           :name "Should prompt the user to select an account to revoke"
           :tags [:revoke-account]
           :bind {:callback-query-id (generate-callback-query-id)
                  :contains-all [:virtual-personal-account-name]}
           :take [:accounts-mgmt-msg :created-account]
           :give :account-to-revoke-selection-msg

           :update {:type :callback_query
                    :data (fn [ctx]
                            {:id (:callback-query-id ctx)
                             :chat {:id (:chat-id ctx) :title (:chat-title ctx) :type "group"}
                             :from {:id (:user-id ctx) :first_name (:user-name ctx) :language_code "ru"}
                             :message (-> ctx :deps :accounts-mgmt-msg)
                             :data "<accounts/revoke>"})}
           :checks {:result (fn [ctx]
                              {:method "answerCallbackQuery"
                               :callback_query_id (:callback-query-id ctx)})
                    :chat-data {:chat-state :ready
                                :bot-messages (fn [ctx]
                                                [{:msg-id (-> ctx :deps :settings-msg :message_id)
                                                  :msg-state [:settings :account-revocation]}])}
                    :responses {:total 1
                                :assert-preds (fn [ctx]
                                                [(fn [res]
                                                   (and (= (:chat-id ctx) (-> res :chat :id))
                                                        (some? (:reply_markup res))
                                                        (contains-all? res :text (:contains-all ctx))
                                                        ;; NB: User cannot revoke their own personal account.
                                                        (let [chat-data (get-chat-data (:chat-id ctx))
                                                              ids {:user-id (:user-id ctx)}
                                                              user-pers-acc (get-personal-account chat-data ids)]
                                                          (not-contains? res :text [(:name user-pers-acc)]))))])}}
           :return-fn (fn [_ responses]
                        (first responses))}

          {:type :test/case
           :name "Should restore the settings message & mark the selected account as revoked"
           :tags [:revoke-account]
           :bind {:callback-query-id (generate-callback-query-id)
                  :account-to-revoke-cd :virtual-personal-account-cd
                  :account-to-revoke-name :virtual-personal-account-name}
           :take :account-to-revoke-selection-msg
           :give [:settings-msg :revoked-account]

           :update {:type :callback_query
                    :data (fn [ctx]
                            {:id (:callback-query-id ctx)
                             :chat {:id (:chat-id ctx) :title (:chat-title ctx) :type "group"}
                             :from {:id (:user-id ctx) :first_name (:user-name ctx) :language_code "ru"}
                             :message (-> ctx :deps :account-to-revoke-selection-msg)
                             :data (:account-to-revoke-cd ctx)})}
           :checks {:result (fn [ctx]
                              {:method "answerCallbackQuery"
                               :callback_query_id (:callback-query-id ctx)})
                    :chat-data {:chat-state :ready
                                :bot-messages (fn [ctx]
                                                [{:msg-id (-> ctx :deps :settings-msg :message_id)
                                                  :msg-state [:settings :initial]}])
                                :accounts (fn [ctx]
                                            [{:name (:account-to-revoke-name ctx)
                                              :pred #(:revoked %)
                                              :desc "the selected account is marked as revoked"}])}
                    :responses {:total 2
                                :assert-preds (fn [ctx]
                                                [(fn [res]
                                                   (valid-settings-msg? res (:chat-id ctx)
                                                                        (-> ctx :deps :settings-msg :message_id)))
                                                 (fn [res]
                                                   (= (:chat-id ctx) (-> res :chat :id)))])}}
           :return-fn (fn [ctx responses]
                        (let [resp-pred #(valid-settings-msg? % (:chat-id ctx))
                              revoked-acc (find-personal-account-by-name (get-chat-data (:chat-id ctx))
                                                                         (:account-to-revoke-name ctx))]
                          [(first (filter resp-pred responses))
                           revoked-acc]))}

          enter-the-accounts-menu
          no-eligible-accounts-for-revocation]})

;; NB: To be run twice: 1. when there are no accs; 2. after reinstating an acc.
(def no-eligible-accounts-for-reinstatement
  {:type :test/case
   :name "Should notify user when there's no eligible accounts for reinstatement"
   :tags [:reinstate-account :notifications]
   :bind {:callback-query-id (generate-callback-query-id)}
   :take :accounts-mgmt-msg

   :update {:type :callback_query
            :data (fn [ctx]
                    {:id (:callback-query-id ctx)
                     :chat {:id (:chat-id ctx) :title (:chat-title ctx) :type "group"}
                     :from {:id (:user-id ctx) :first_name (:user-name ctx) :language_code "ru"}
                     :message (-> ctx :deps :accounts-mgmt-msg)
                     :data "<accounts/reinstate>"})}
   :checks {:result (fn [ctx]
                      {:method "answerCallbackQuery"
                       :callback_query_id (:callback-query-id ctx)})
            :chat-data {:chat-state :ready}
            :responses {:total 1
                        :assert-preds [#(= true %)]}}})

(def reinstate-account-test-group
  {:type :test/group
   :name "2-1-4 Reinstate an account"
   :test [{:type :test/case
           :name "Should prompt the user to select an account to reinstate"
           :tags [:reinstate-account]
           :bind {:callback-query-id (generate-callback-query-id)
                  :contains-all [:virtual-personal-account-name]}
           :take [:accounts-mgmt-msg :revoked-account]
           :give :account-to-reinstate-selection-msg

           :update {:type :callback_query
                    :data (fn [ctx]
                            {:id (:callback-query-id ctx)
                             :chat {:id (:chat-id ctx) :title (:chat-title ctx) :type "group"}
                             :from {:id (:user-id ctx) :first_name (:user-name ctx) :language_code "ru"}
                             :message (-> ctx :deps :accounts-mgmt-msg)
                             :data "<accounts/reinstate>"})}
           :checks {:result (fn [ctx]
                              {:method "answerCallbackQuery"
                               :callback_query_id (:callback-query-id ctx)})
                    :chat-data {:chat-state :ready
                                :bot-messages (fn [ctx]
                                                [{:msg-id (-> ctx :deps :settings-msg :message_id)
                                                  :msg-state [:settings :account-reinstatement]}])}
                    :responses {:total 1
                                :assert-preds (fn [ctx]
                                                [(fn [res]
                                                   (and (= (:chat-id ctx) (-> res :chat :id))
                                                        (some? (:reply_markup res))
                                                        (contains-all? res :text (:contains-all ctx))
                                                        ;; NB: User cannot reinstate their own personal account.
                                                        (let [chat-data (get-chat-data (:chat-id ctx))
                                                              ids {:user-id (:user-id ctx)}
                                                              user-pers-acc (get-personal-account chat-data ids)]
                                                          (not-contains? res :text [(:name user-pers-acc)]))))])}}
           :return-fn (fn [_ responses]
                        (first responses))}

          {:type :test/case
           :name "Should restore the settings message & reinstate the selected account"
           :tags [:reinstate-account]
           :bind {:callback-query-id (generate-callback-query-id)
                  :account-to-reinstate-cd :virtual-personal-account-cd
                  :account-to-reinstate-name :virtual-personal-account-name}
           :take :account-to-reinstate-selection-msg

           :update {:type :callback_query
                    :data (fn [ctx]
                            {:id (:callback-query-id ctx)
                             :chat {:id (:chat-id ctx) :title (:chat-title ctx) :type "group"}
                             :from {:id (:user-id ctx) :first_name (:user-name ctx) :language_code "ru"}
                             :message (-> ctx :deps :account-to-reinstate-selection-msg)
                             :data (:account-to-reinstate-cd ctx)})}
           :checks {:result (fn [ctx]
                              {:method "answerCallbackQuery"
                               :callback_query_id (:callback-query-id ctx)})
                    :chat-data {:chat-state :ready
                                :bot-messages (fn [ctx]
                                                [{:msg-id (-> ctx :deps :settings-msg :message_id)
                                                  :msg-state [:settings :initial]}])
                                :accounts (fn [ctx]
                                            [{:name (:account-to-reinstate-name ctx)
                                              :pred #(not (:revoked %))
                                              :desc "the selected account is reinstated"}])}
                    :responses {:total 2
                                :assert-preds (fn [ctx]
                                                [(fn [res]
                                                   (valid-settings-msg? res (:chat-id ctx)
                                                                        (-> ctx :deps :settings-msg :message_id)))
                                                 (fn [res]
                                                   (= (:chat-id ctx) (-> res :chat :id)))])}}}

          enter-the-accounts-menu
          no-eligible-accounts-for-reinstatement]})

(def accounts-mgmt-test-group
  {:type :test/group
   :name "2-1 Accounts Management"
   :bind {:virtual-personal-account-name (generate-name-str "Acc/")
          :virtual-personal-account-cd "ac::personal::1"}
   :test [enter-the-accounts-menu

          #{no-eligible-accounts-for-revocation
            no-eligible-accounts-for-reinstatement}
          #{[create-virtual-personal-account-test-group
             [enter-the-accounts-menu
              #{[revoke-account-test-group
                 reinstate-account-test-group]
                rename-virtual-personal-account-test-group}]]
            rename-user-personal-account-test-group

            exit-the-accounts-menu}]})

(def settings-test-group
  {:type :test/group
   :name "2 Settings"
   :test [accounts-mgmt-test-group]})

(def use-cases
  [{:type :test/group
    :name "Use Case 1. Personal accounting (single user)"
    :bind {:user-id (generate-user-id)
           :user-name (generate-name-str "User/")}
    :test [{:type :test/group
            :name "Group Chat"
            :bind {:chat-id (generate-chat-id)
                   :chat-title (generate-name-str 3 "Group/")
                   :user-personal-account-name (generate-name-str "Acc/")
                   :user-personal-account-cd "ac::personal::0"}
            :test [start-new-chat-test-group
                   settings-test-group]}]}])

; Tests Composition

(defn- unit-update-test
  "A unit test of the incoming update for a Telegram bot."
  [{:keys [update checks return-fn] :as _test-case} ctx]
  {:pre [(some? update)]}
  (let [get-value-with-ctx (fn [map key]
                             (let [val-or-fn (get map key)]
                               (if (fn? val-or-fn) (val-or-fn ctx) val-or-fn)))
        [result responses] (with-mock-send
                             (or (:mock-fns update) {})
                             #(let [update (build-update (:type update)
                                                         (get-value-with-ctx update :data))
                                    result (bot-api update)]
                                [result (collect-responses)]))]
    ;; operation code / immediate response
    (when-some [exp-result (get-value-with-ctx checks :result)]
      (is (= exp-result result)))

    ;; chat data state
    (encore/when-some [exp-chat-data (:chat-data checks)
                       chat-data (get-chat-data (:chat-id ctx))]
      (when (some? (:members-count exp-chat-data))
        (is (= (:members-count exp-chat-data) (get-members-count chat-data))
            "members count"))
      (when (some? (:chat-state exp-chat-data))
        (is (= (:chat-state exp-chat-data) (get-chat-state chat-data))
            "chat state"))
      (doseq [account (get-value-with-ctx exp-chat-data :accounts)]
        (is (let [acc (find-personal-account-by-name chat-data (:name account))]
              (and (some? acc)
                   (or (not (contains? account :pred))
                       ((:pred account) acc))))
            (or (:desc account)
                "an account with the specified name exists")))
      (doseq [bot-message (get-value-with-ctx exp-chat-data :bot-messages)]
        (is (= (:msg-state bot-message)
               (get-bot-msg-state chat-data (:msg-id bot-message)))
            "msg state")))

    ;; bot responses
    (when-some [exp-responses (:responses checks)]
      (when (some? (:total exp-responses))
        (is (= (:total exp-responses) (count responses))
            "total responses"))
      (when-some [resp-assert-preds (get-value-with-ctx exp-responses :assert-preds)]
        (is (apply do-responses-match? responses resp-assert-preds)
            "responses match all predicates")))

    (when (some? return-fn)
      (return-fn ctx responses))))

(defn- to-zipper
  [test-groups]
  (zip/zipper #(or (vector? %) (set? %) (:test %))
              #(if (or (vector? %) (set? %)) (seq %) (:test %))
              (fn [node children] (with-meta children (meta node)))
              test-groups))

(defn- assert-test-params
  [ctx case]
  (let [required-params (if (contains? case :take)
                          (set (utils/ensure-vec (:take case)))
                          #{})
        provided-params (or (-> (:deps ctx) keys set) #{})
        missing-params (set/difference required-params provided-params)]
    (assert (empty? missing-params)
            (format "Missing test case parameter(s) %s in TC \"%s\""
                    missing-params (:name case)))))

(defn- set-bindings
  [ctx binds]
  (into ctx (map #(cond
                    (keyword? (val %))
                    [(key %) (get ctx (val %))]

                    (vector? (val %))
                    [(key %) (replace ctx (val %))]

                    :else %)
                 binds)))

(defn- traverse-tests
  "Recursively runs the tests of some test tree (consisting of test groups and
   individual test cases) in a depth-first, pre-order traversal.

   The following parameters are accepted:
   - loc               — a zipper structure (tree+location) over the test tree,
   - ctx               — a context of a test execution (which is hierarchical),
   - results           — the results of all previous tests executions that are
                         gathered together in a rolling updates fashion (which
                         reflects how they are gathered in real use cases),
   - run-independently — an indicator set of test cases or groups that need to
                         be executed independently, i.e. without affecting the
                         shared state (*bot-data) and the 'results' passed on."
  ([loc]
   (traverse-tests loc {} {}))
  ([loc ctx results]
   (traverse-tests loc ctx results #{}))
  ([loc ctx results run-independently]
   (let [node (when (some? loc) (zip/node loc))]
     (cond
       (or (nil? loc) (zip/end? loc))
       results

       ;; These tests are ordered, so must run them one-by-one, since they depend on
       ;; each other's results and side-effects (the shared state is the 'bot-data').
       (vector? node)
       (if (and (seq run-independently)
                (run-independently node))
         (let [upd-loc (zip/edit loc (fn [node] {:type :test/group
                                                 :test node}))]
           ; NB: We run this tests independently as an anonymous test group.
           (recur upd-loc ctx results (-> run-independently
                                          (disj node)
                                          (conj (zip/node upd-loc)))))
         (recur (zip/next loc) ctx results run-independently))

       ;; These tests are unordered, so we must run them independently of each other,
       ;; as if they were the "parallel" versions of what might have happened to the
       ;; same initial 'bot-data'.
       (set? node)
       (recur (zip/next loc) ctx results (set/union run-independently node))

       (= :test/group (:type node))
       (let [ctx (set-bindings ctx (:bind node)) ;; should promote further
             run-tg (fn []
                      (let [tg-zipper (to-zipper (:test node))]
                        (testing (str (:name node) " ::")
                          (traverse-tests tg-zipper ctx results))))]
         (if (and (seq run-independently)
                  (run-independently node))
           (do
             (reset-bot-data-afterwards run-tg)
             (recur (zip/right loc) ctx results (disj run-independently node)))
           (let [tg-results (run-tg)
                 upd-results (merge results tg-results)]
             (recur (zip/right loc) ctx upd-results run-independently))))

       (= :test/case (:type node))
       (let [run-tc (fn []
                      (let [ctx (-> ctx
                                    (assoc :deps results)
                                    (set-bindings (:bind node)))
                            case (dissoc node :bind)]
                        (assert-test-params ctx case)
                        (testing (:name case)
                          (unit-update-test case ctx))))]
         (if (and (seq run-independently)
                  (run-independently node))
           (do
             (reset-bot-data-afterwards run-tc)
             (recur (zip/next loc) ctx results (disj run-independently node)))
           (let [tc-result (run-tc)
                 get-give (fn [case]
                            (utils/ensure-vec (:give case)))
                 upd-results (if (some? tc-result)
                               (->> (utils/ensure-vec tc-result)
                                    (interleave (get-give node))
                                    (apply assoc results))
                               results)]
             (recur (zip/next loc) ctx upd-results run-independently))))

       :else ;; stops the whole process
       (throw (IllegalStateException. (str "Unexpected node type: " node)))))))

(defn- comp-update-test
  "A composite test of the incoming update for a Telegram bot."
  [test-groups]
  (traverse-tests (to-zipper test-groups)))

(deftest user-scenarios
  (comp-update-test use-cases))
