(ns general-expenses-accountant.core-test
  (:refer-clojure :exclude [reduce])
  (:require [clojure.test :refer [use-fixtures deftest testing is]]
            [clojure.string :as str]
            [clojure.core.async :refer [go chan >! <!! close! reduce]]

            [taoensso.encore :refer [defalias]]
            [mount.core :as mount]

            [general-expenses-accountant.core :as core
             :refer [bot-api op-succeed get-datetime-in-tg-format]]
            [general-expenses-accountant.domain.chat :as chats]
            [general-expenses-accountant.domain.tlog :as tlogs]

            [general-expenses-accountant.tg-client :as tg-client])
  (:import [java.util.concurrent.atomic AtomicInteger]))

;; TODO: Implement the test coverage in a form of externally stored (in files) web requests series (run by scripts).

;; TODO: Learn how to properly mock 'clj-http' requests?

; Aliases (of required private "getter" fns)

(defalias get-chat-data core/get-chat-data)
(defalias get-members-count core/get-members-count)
(defalias get-chat-state core/get-chat-state)
(defalias get-bot-msg-id core/get-bot-msg-id)
(defalias get-bot-msg-state core/get-bot-msg-state)
(defalias change-bot-msg-state! core/change-bot-msg-state!*) ;; TODO: Get rid of this.
(defalias get-personal-account core/get-personal-account)
(defalias find-personal-account-by-name core/find-personal-account-by-name)

; Shared State

(def ^:private bot-user
  {:id 1,
   :is_bot true,
   :first_name "Bot #1",
   :username "number_one_bot"})

(def ^:private *bot-data (atom {}))

(defn start-required-states [f]
  (-> (mount/only #{;#'config/loader ;; TODO: Use another config for tests.
                    #'general-expenses-accountant.core/bot-user
                    #'general-expenses-accountant.core/*bot-data})
      (mount/swap {#'general-expenses-accountant.core/bot-user bot-user
                   #'general-expenses-accountant.core/*bot-data *bot-data})
      mount/start)
  (f))

(use-fixtures :once start-required-states)

(def ^{:private true :tag 'AtomicInteger} latest-chat-id (AtomicInteger.))
(def ^{:private true :tag 'AtomicInteger} latest-user-id (AtomicInteger.))
(def ^{:private true :tag 'AtomicInteger} latest-update-id (AtomicInteger.))
(def ^{:private true :tag 'AtomicInteger} latest-message-id (AtomicInteger.))
(def ^{:private true :tag 'AtomicInteger} latest-callback-query-id (AtomicInteger.))

;(use-fixtures :each (fn [f] setup... (f) cleanup...))

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
                              :reply_markup (-> response :options :reply_markup)}
                             {:message_id (generate-message-id)
                              :from bot-user
                              :chat chat
                              :date (get-datetime-in-tg-format)
                              :text (:text response)
                              :reply_markup (-> response :options :reply_markup)})))
                 :callback true)]
    {:ok true
     :result result}))

(defn- build-update
  [upd-type upd-data & _params]
  {:pre [(keyword? upd-type) (map? upd-data)]}
  ;; TODO: Treat the 'upd-data' as a template,
  ;;       substitute placeholders w/ params.
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

(def collect-responses (constantly []))

(defn- get-total-responses
  []
  (count (collect-responses)))

(defn- get-response
  [n]
  {:pre [(pos-int? n)]}
  (nth (collect-responses) (dec n)))

(defn- do-responses-match?
  [& resp-assert-preds]
  (loop [resps (collect-responses)
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

; Test Cases

(deftest personal-accounting
  (testing "Use Case 1. Personal accounting (single user)"

    (testing ":: Group Chat"
      (let [chat-id (generate-chat-id)
            chat-title (generate-name-str 3 "Group/")
            user-id (generate-user-id)
            user-name (generate-name-str "User/")
            settings-msg (promise)]

        (testing ":: 1 Start a new chat"
          (let [name-request-msg (promise)
                user-personal-account-name (generate-name-str "Acc/")]

            (testing ":: 1-1 Create chat with bot"
              (with-mock-send
                {#'tg-client/get-chat-members-count (constantly 2)}
                #(let [update (build-update :my_chat_member
                                            {:chat {:id chat-id :title chat-title :type "group"}
                                             :from {:id user-id :first_name user-name :language_code "ru"}
                                             :date (get-datetime-in-tg-format)
                                             :old_chat_member {:user bot-user :status "left"}
                                             :new_chat_member {:user bot-user :status "member"}})
                       result (bot-api update)
                       chat-data (get-chat-data chat-id)]
                   ;; operation code / immediate response
                   (is (= op-succeed result))

                   ;; chat data state
                   (is (= 2 (get-members-count chat-data)) "members count")
                   (is (= :waiting (get-chat-state chat-data)) "chat state")

                   ;; bot responses
                   (is (= 2 (get-total-responses)) "total responses")
                   (is (do-responses-match?
                         (fn [res]
                           (and (= chat-id (-> res :chat :id))
                                (str/includes? (:text res) user-name)))
                         (fn [res]
                           (when (and (= chat-id (-> res :chat :id))
                                      (= (get-bot-msg-id chat-id :name-request-msg-id)
                                         (:message_id res)))
                             (deliver name-request-msg res)
                             true)))
                       "responses match all predicates"))))

            ;; TODO: Add a TC that checks that other interactions are ineffectual at this point.

            (testing ":: 1-2 Reply to the name request message"
              (with-mock-send
                {#'core/can-write-to-user? (constantly true)} ;; as if user has already started some other chat
                #(let [update (build-update :message
                                            {:message_id (generate-message-id)
                                             :chat {:id chat-id :title chat-title :type "group"}
                                             :from {:id user-id :first_name user-name :language_code "ru"}
                                             :date (get-datetime-in-tg-format)
                                             :reply_to_message @name-request-msg
                                             :text user-personal-account-name})
                       result (bot-api update)
                       chat-data (get-chat-data chat-id)]
                   ;; operation code / immediate response
                   (is (= op-succeed result))

                   ;; chat data state
                   (is (= 2 (get-members-count chat-data)) "members count")
                   (is (= :ready (get-chat-state chat-data)) "chat state")
                   (let [new-acc (find-personal-account-by-name chat-data user-personal-account-name)]
                     (is (some? new-acc) "an account with the specified name exists"))

                   ;; bot responses
                   (is (= 3 (get-total-responses)) "total responses")
                   (is (do-responses-match?
                         (fn [res]
                           (and (= user-id (-> res :chat :id))
                                (str/includes? (:text res) chat-title)))
                         (fn [res]
                           (and (= chat-id (-> res :chat :id))
                                (some? (:reply_markup res))
                                (= "https://t.me/number_one_bot"
                                   (:url (get-inline-kbd-btn res 0 0)))))
                         (fn [res]
                           (when (and (= chat-id (-> res :chat :id))
                                      (some? (:reply_markup res))
                                      (= ["<accounts>" "<expense_items>" "<shares>"]
                                         (mapv :callback_data (get-inline-kbd-row res 0)))
                                      (= [:settings :initial]
                                         (get-bot-msg-state chat-data (:message_id res))))
                             (deliver settings-msg res)
                             true)))
                       "responses match all predicates"))))))

        (testing ":: 2 Settings"
          (let [settings-msg-id (:message_id @settings-msg)
                reset-settings-state! (fn []
                                        (with-mock-db
                                          #(let [curr-state (get-bot-msg-state (get-chat-data chat-id) settings-msg-id)]
                                             (when-not (= [:settings :initial] curr-state)
                                               (change-bot-msg-state! chat-id :settings settings-msg-id :initial)))))]

            (testing ":: 2-1 Accounts Management"
              (let [enter-accounts-mgmt! (fn [callback-query-id]
                                           (with-mock-send
                                             #(let [update (build-update :callback_query
                                                                         {:id callback-query-id
                                                                          :chat {:id chat-id :title chat-title :type "group"}
                                                                          :from {:id user-id :first_name user-name :language_code "ru"}
                                                                          :message @settings-msg
                                                                          :data "<accounts>"})
                                                    result (bot-api update)
                                                    chat-data (get-chat-data chat-id)]
                                                [result chat-data (collect-responses)])))
                    virtual-personal-account-name (generate-name-str "Acc/")
                    virtual-personal-account-cd "ac::personal::1"]

                (testing ":: 2-1-0 Menu navigation"
                  (reset-settings-state!)
                  (let [callback-query-id (generate-callback-query-id)
                        [result chat-data [accounts-mgmt-msg & rest]] (enter-accounts-mgmt! callback-query-id)
                        user-personal-account-name (:name (get-personal-account chat-data {:user-id user-id}))]

                    (testing ":: Enter the 'Accounts' menu"
                      ;; operation code / immediate response
                      (is (= {:method "answerCallbackQuery"
                              :callback_query_id callback-query-id} result))

                      ;; chat data state
                      (is (= :ready (get-chat-state chat-data)) "chat state")
                      (is (= [:settings :accounts-mgmt]
                             (get-bot-msg-state chat-data settings-msg-id)) "msg state")

                      ;; bot responses
                      (is (= 0 (count rest)) "total responses")
                      (let [res accounts-mgmt-msg]
                        (is (= chat-id (-> res :chat :id)))
                        (is (str/includes? (:text res) user-personal-account-name))
                        (is (some? (:reply_markup res)))
                        (is (every? (set (map :callback_data (get-inline-kbd-col res 0)))
                                    ["<accounts/create>" "<accounts/rename>"
                                     "<accounts/revoke>" "<accounts/reinstate>"]))
                        res))

                    (testing ":: Exit the 'Accounts' menu"
                      (with-mock-send
                        #(let [callback-query-id (generate-callback-query-id)
                               update (build-update :callback_query
                                                    {:id callback-query-id
                                                     :chat {:id chat-id :title chat-title :type "group"}
                                                     :from {:id user-id :first_name user-name :language_code "ru"}
                                                     :message accounts-mgmt-msg
                                                     :data "<back>"})
                               result (bot-api update)
                               chat-data (get-chat-data chat-id)]
                           ;; operation code / immediate response
                           (is (= {:method "answerCallbackQuery"
                                   :callback_query_id callback-query-id} result))

                           ;; chat data state
                           (is (= :ready (get-chat-state chat-data)) "chat state")
                           (is (= [:settings :initial]
                                  (get-bot-msg-state chat-data settings-msg-id)) "msg state")

                           ;; bot responses
                           (is (= 1 (get-total-responses)) "total responses")
                           (is (do-responses-match?
                                 (fn [res]
                                   ;; TODO: This checks group may be extracted into fn.
                                   (and (= chat-id (-> res :chat :id))
                                        (= settings-msg-id (:message_id res))
                                        (some? (:reply_markup res))
                                        (= ["<accounts>" "<expense_items>" "<shares>"]
                                           (mapv :callback_data (get-inline-kbd-row res 0))))))
                               "responses match all predicates"))))))

                (testing ":: 2-1-1 Create a new account"
                  (reset-settings-state!)
                  (let [[_ _ [accounts-mgmt-msg]] (enter-accounts-mgmt! (generate-callback-query-id))
                        create-account-msg (promise)
                        new-account-name-request-msg (promise)]

                    (testing ":: Should prompt the user to select the type of a new account"
                      (with-mock-send
                        #(let [callback-query-id (generate-callback-query-id)
                               update (build-update :callback_query
                                                    {:id callback-query-id
                                                     :chat {:id chat-id :title chat-title :type "group"}
                                                     :from {:id user-id :first_name user-name :language_code "ru"}
                                                     :message accounts-mgmt-msg
                                                     :data "<accounts/create>"})
                               result (bot-api update)
                               chat-data (get-chat-data chat-id)]
                           ;; operation code / immediate response
                           (is (= {:method "answerCallbackQuery"
                                   :callback_query_id callback-query-id} result))

                           ;; chat data state
                           (is (= :ready (get-chat-state chat-data)) "chat state")
                           (is (= [:settings :account-type-selection]
                                  (get-bot-msg-state chat-data settings-msg-id)) "msg state")

                           ;; bot responses
                           (is (= 1 (get-total-responses)) "total responses")
                           (is (do-responses-match?
                                 (fn [res]
                                   (when (and (= chat-id (-> res :chat :id))
                                              (some? (:reply_markup res))
                                              (every? (set (map :callback_data (get-inline-kbd-col res 0)))
                                                      ["at::group" "at::personal"]))
                                     (deliver create-account-msg res)
                                     true)))
                               "responses match all predicates"))))

                    (testing ":: Should restore the settings message & prompt the user for an account name"
                      (with-mock-send
                        #(let [callback-query-id (generate-callback-query-id)
                               update (build-update :callback_query
                                                    {:id callback-query-id
                                                     :chat {:id chat-id :title chat-title :type "group"}
                                                     :from {:id user-id :first_name user-name :language_code "ru"}
                                                     :message @create-account-msg
                                                     :data "at::personal"})
                               result (bot-api update)
                               chat-data (get-chat-data chat-id)]
                           ;; operation code / immediate response
                           (is (= {:method "answerCallbackQuery"
                                   :callback_query_id callback-query-id} result))

                           ;; chat data state
                           (is (= :waiting (get-chat-state chat-data)) "chat state")
                           (is (= [:settings :initial]
                                  (get-bot-msg-state chat-data settings-msg-id)) "msg state")

                           ;; bot responses
                           (is (= 2 (get-total-responses)) "total responses")
                           (is (do-responses-match?
                                 (fn [res]
                                   ;; TODO: This checks group may be extracted into fn.
                                   (and (= chat-id (-> res :chat :id))
                                        (= settings-msg-id (:message_id res))
                                        (some? (:reply_markup res))
                                        (= ["<accounts>" "<expense_items>" "<shares>"]
                                           (mapv :callback_data (get-inline-kbd-row res 0)))))
                                 (fn [res]
                                   (when (and (= chat-id (-> res :chat :id))
                                              (some? (:reply_markup res))
                                              (-> res :reply_markup :force_reply)
                                              (-> res :reply_markup :selective)
                                              (str/includes? (:text res) user-name)
                                              (= (get-bot-msg-id chat-id [:to-user user-id :request-acc-name-msg-id])
                                                 (:message_id res)))
                                     (deliver new-account-name-request-msg res)
                                     true)))
                               "responses match all predicates"))))

                    ;; TODO: Add a TC that checks that other interactions are ineffectual at this point.

                    (testing ":: Should create a new account of the selected type and with the specified name"
                      (with-mock-send
                        #(let [update (build-update :message
                                                    {:message_id (generate-message-id)
                                                     :chat {:id chat-id :title chat-title :type "group"}
                                                     :from {:id user-id :first_name user-name :language_code "ru"}
                                                     :date (get-datetime-in-tg-format)
                                                     :reply_to_message @new-account-name-request-msg
                                                     :text virtual-personal-account-name})
                               result (bot-api update)
                               chat-data (get-chat-data chat-id)]
                           ;; operation code / immediate response
                           (is (= op-succeed result))

                           ;; chat data state
                           (is (= :ready (get-chat-state chat-data)) "chat state")
                           (let [new-acc (find-personal-account-by-name chat-data virtual-personal-account-name)]
                             (is (some? new-acc) "a personal account with the specified name exists"))

                           ;; bot responses
                           (is (= 1 (get-total-responses)) "total responses")
                           (is (= chat-id (-> (get-response 1) :chat :id))))))))

                (testing ":: 2-1-2 Rename an account"
                  (reset-settings-state!)
                  (let [[_ _ [accounts-mgmt-msg]] (enter-accounts-mgmt! (generate-callback-query-id))
                        account-to-rename-selection-msg (promise)
                        account-rename-request-msg (promise)
                        user-personal-account-new-name (generate-name-str "Acc/")]

                    (testing ":: Should prompt the user to select an account to rename"
                      (with-mock-send
                        #(let [callback-query-id (generate-callback-query-id)
                               update (build-update :callback_query
                                                    {:id callback-query-id
                                                     :chat {:id chat-id :title chat-title :type "group"}
                                                     :from {:id user-id :first_name user-name :language_code "ru"}
                                                     :message accounts-mgmt-msg
                                                     :data "<accounts/rename>"})
                               result (bot-api update)
                               chat-data (get-chat-data chat-id)
                               user-personal-account-name (:name (get-personal-account chat-data {:user-id user-id}))]
                           ;; operation code / immediate response
                           (is (= {:method "answerCallbackQuery"
                                   :callback_query_id callback-query-id} result))

                           ;; chat data state
                           (is (= :ready (get-chat-state chat-data)) "chat state")
                           (is (= [:settings :account-renaming]
                                  (get-bot-msg-state chat-data settings-msg-id)) "msg state")

                           ;; bot responses
                           (is (= 1 (get-total-responses)) "total responses")
                           (is (do-responses-match?
                                 (fn [res]
                                   (when (and (= chat-id (-> res :chat :id))
                                              (some? (:reply_markup res))
                                              ;; TODO: Extract it to a predicate fn w/ a meaningful name.
                                              (every? (set (map :text (get-inline-kbd-col res 0)))
                                                      [user-personal-account-name virtual-personal-account-name]))
                                     (deliver account-to-rename-selection-msg res)
                                     true)))
                               "responses match all predicates"))))

                    (testing ":: Should restore the settings message & prompt the user for an account name"
                      (with-mock-send
                        #(let [callback-query-id (generate-callback-query-id)
                               update (build-update :callback_query
                                                    {:id callback-query-id
                                                     :chat {:id chat-id :title chat-title :type "group"}
                                                     :from {:id user-id :first_name user-name :language_code "ru"}
                                                     :message @account-to-rename-selection-msg
                                                     :data "ac::personal::0"})
                               result (bot-api update)
                               chat-data (get-chat-data chat-id)
                               user-personal-account-name (:name (get-personal-account chat-data {:user-id user-id}))]
                           ;; operation code / immediate response
                           (is (= {:method "answerCallbackQuery"
                                   :callback_query_id callback-query-id} result))

                           ;; chat data state
                           (is (= :waiting (get-chat-state chat-data)) "chat state")
                           (is (= [:settings :initial]
                                  (get-bot-msg-state chat-data settings-msg-id)) "msg state")

                           ;; bot responses
                           (is (= 2 (get-total-responses)) "total responses")
                           (is (do-responses-match?
                                 (fn [res]
                                   ;; TODO: This checks group may be extracted into fn.
                                   (and (= chat-id (-> res :chat :id))
                                        (= settings-msg-id (:message_id res))
                                        (some? (:reply_markup res))
                                        (= ["<accounts>" "<expense_items>" "<shares>"]
                                           (mapv :callback_data (get-inline-kbd-row res 0)))))
                                 (fn [res]
                                   (when (and (= chat-id (-> res :chat :id))
                                              (some? (:reply_markup res))
                                              (-> res :reply_markup :force_reply)
                                              (-> res :reply_markup :selective)
                                              (str/includes? (:text res) user-name)
                                              (str/includes? (:text res) user-personal-account-name)
                                              (= (get-bot-msg-id chat-id [:to-user user-id :request-rename-msg-id])
                                                 (:message_id res)))
                                     (deliver account-rename-request-msg res)
                                     true)))
                               "responses match all predicates"))))

                    ;; TODO: Add a TC that checks that other interactions are ineffectual at this point.

                    (testing ":: Should update an existing account with the specified name"
                      (with-mock-send
                        #(let [update (build-update :message
                                                    {:message_id (generate-message-id)
                                                     :chat {:id chat-id :title chat-title :type "group"}
                                                     :from {:id user-id :first_name user-name :language_code "ru"}
                                                     :date (get-datetime-in-tg-format)
                                                     :reply_to_message @account-rename-request-msg
                                                     :text user-personal-account-new-name})
                               result (bot-api update)
                               chat-data (get-chat-data chat-id)]
                           ;; operation code / immediate response
                           (is (= op-succeed result))

                           ;; chat data state
                           (is (= :ready (get-chat-state chat-data)) "chat state")
                           (let [new-acc (find-personal-account-by-name chat-data user-personal-account-new-name)]
                             (is (some? new-acc) "a personal account with the specified name exists"))

                           ;; bot responses
                           (is (= 1 (get-total-responses)) "total responses")
                           (is (= chat-id (-> (get-response 1) :chat :id))))))))

                (testing ":: 2-1-3 Revoke an account"
                  (reset-settings-state!)
                  (let [[_ _ [accounts-mgmt-msg]] (enter-accounts-mgmt! (generate-callback-query-id))
                        account-to-revoke-selection-msg (promise)
                        account-to-revoke-cd virtual-personal-account-cd]

                    (testing ":: Should prompt the user to select an account to revoke"
                      (with-mock-send
                        #(let [callback-query-id (generate-callback-query-id)
                               update (build-update :callback_query
                                                    {:id callback-query-id
                                                     :chat {:id chat-id :title chat-title :type "group"}
                                                     :from {:id user-id :first_name user-name :language_code "ru"}
                                                     :message accounts-mgmt-msg
                                                     :data "<accounts/revoke>"})
                               result (bot-api update)
                               chat-data (get-chat-data chat-id)
                               user-personal-account-name (:name (get-personal-account chat-data {:user-id user-id}))]
                           ;; operation code / immediate response
                           (is (= {:method "answerCallbackQuery"
                                   :callback_query_id callback-query-id} result))

                           ;; chat data state
                           (is (= :ready (get-chat-state chat-data)) "chat state")
                           (is (= [:settings :account-revocation]
                                  (get-bot-msg-state chat-data settings-msg-id)) "msg state")

                           ;; bot responses
                           (is (= 1 (get-total-responses)) "total responses")
                           (is (do-responses-match?
                                 (fn [res]
                                   (when (and (= chat-id (-> res :chat :id))
                                              (some? (:reply_markup res))
                                              ;; TODO: Extract it to a predicate fn w/ a meaningful name.
                                              (every? (set (map :text (get-inline-kbd-col res 0)))
                                                      [virtual-personal-account-name])
                                              (not-any? (set (map :text (get-inline-kbd-col res 0)))
                                                        [user-personal-account-name]))
                                     (deliver account-to-revoke-selection-msg res)
                                     true)))
                               "responses match all predicates"))))

                    (testing ":: Should restore the settings message & mark the selected account as revoked"
                      (with-mock-send
                        #(let [callback-query-id (generate-callback-query-id)
                               update (build-update :callback_query
                                                    {:id callback-query-id
                                                     :chat {:id chat-id :title chat-title :type "group"}
                                                     :from {:id user-id :first_name user-name :language_code "ru"}
                                                     :message @account-to-revoke-selection-msg
                                                     :data account-to-revoke-cd})
                               result (bot-api update)
                               chat-data (get-chat-data chat-id)]
                           ;; operation code / immediate response
                           (is (= {:method "answerCallbackQuery"
                                   :callback_query_id callback-query-id} result))

                           ;; chat data state
                           (is (= :ready (get-chat-state chat-data)) "chat state")
                           (is (= [:settings :initial]
                                  (get-bot-msg-state chat-data settings-msg-id)) "msg state")
                           (let [acc (find-personal-account-by-name chat-data virtual-personal-account-name)]
                             (is (:revoked acc) "the selected account is marked as revoked"))

                           ;; bot responses
                           (is (= 2 (get-total-responses)) "total responses")
                           (is (do-responses-match?
                                 (fn [res]
                                   ;; TODO: This checks group may be extracted into fn.
                                   (and (= chat-id (-> res :chat :id))
                                        (= settings-msg-id (:message_id res))
                                        (some? (:reply_markup res))
                                        (= ["<accounts>" "<expense_items>" "<shares>"]
                                           (mapv :callback_data (get-inline-kbd-row res 0)))))
                                 (fn [res]
                                   (= chat-id (-> res :chat :id))))
                               "responses match all predicates")))))

                  (let [[_ _ [accounts-mgmt-msg]] (enter-accounts-mgmt! (generate-callback-query-id))]
                    (testing ":: Should notify user when there's no eligible accounts"
                      (with-mock-send
                        #(let [callback-query-id (generate-callback-query-id)
                               update (build-update :callback_query
                                                    {:id callback-query-id
                                                     :chat {:id chat-id :title chat-title :type "group"}
                                                     :from {:id user-id :first_name user-name :language_code "ru"}
                                                     :message accounts-mgmt-msg
                                                     :data "<accounts/revoke>"})
                               result (bot-api update)
                               chat-data (get-chat-data chat-id)]
                           ;; operation code / immediate response
                           (is (= {:method "answerCallbackQuery"
                                   :callback_query_id callback-query-id} result))

                           ;; chat data state
                           (is (= :ready (get-chat-state chat-data)) "chat state")

                           ;; bot responses
                           (is (= 1 (get-total-responses)) "total responses")
                           (is (= true (get-response 1))))))))

                (testing ":: 2-1-4 Reinstate an account"
                  (reset-settings-state!)
                  (let [[_ _ [accounts-mgmt-msg]] (enter-accounts-mgmt! (generate-callback-query-id))
                        account-to-reinstate-selection-msg (promise)
                        account-to-reinstate-cd virtual-personal-account-cd]

                    (testing ":: Should prompt the user to select an account to reinstate"
                      (with-mock-send
                        #(let [callback-query-id (generate-callback-query-id)
                               update (build-update :callback_query
                                                    {:id callback-query-id
                                                     :chat {:id chat-id :title chat-title :type "group"}
                                                     :from {:id user-id :first_name user-name :language_code "ru"}
                                                     :message accounts-mgmt-msg
                                                     :data "<accounts/reinstate>"})
                               result (bot-api update)
                               chat-data (get-chat-data chat-id)
                               user-personal-account-name (:name (get-personal-account chat-data {:user-id user-id}))]
                           ;; operation code / immediate response
                           (is (= {:method "answerCallbackQuery"
                                   :callback_query_id callback-query-id} result))

                           ;; chat data state
                           (is (= :ready (get-chat-state chat-data)) "chat state")
                           (is (= [:settings :account-reinstatement]
                                  (get-bot-msg-state chat-data settings-msg-id)) "msg state")

                           ;; bot responses
                           (is (= 1 (get-total-responses)) "total responses")
                           (is (do-responses-match?
                                 (fn [res]
                                   (when (and (= chat-id (-> res :chat :id))
                                              (some? (:reply_markup res))
                                              ;; TODO: Extract it to a predicate fn w/ a meaningful name.
                                              (every? (set (map :text (get-inline-kbd-col res 0)))
                                                      [virtual-personal-account-name])
                                              (not-any? (set (map :text (get-inline-kbd-col res 0)))
                                                        [user-personal-account-name]))
                                     (deliver account-to-reinstate-selection-msg res)
                                     true)))
                               "responses match all predicates"))))

                    (testing ":: Should restore the settings message & reinstate the selected account"
                      (with-mock-send
                        #(let [callback-query-id (generate-callback-query-id)
                               update (build-update :callback_query
                                                    {:id callback-query-id
                                                     :chat {:id chat-id :title chat-title :type "group"}
                                                     :from {:id user-id :first_name user-name :language_code "ru"}
                                                     :message @account-to-reinstate-selection-msg
                                                     :data account-to-reinstate-cd})
                               result (bot-api update)
                               chat-data (get-chat-data chat-id)]
                           ;; operation code / immediate response
                           (is (= {:method "answerCallbackQuery"
                                   :callback_query_id callback-query-id} result))

                           ;; chat data state
                           (is (= :ready (get-chat-state chat-data)) "chat state")
                           (is (= [:settings :initial]
                                  (get-bot-msg-state chat-data settings-msg-id)) "msg state")
                           (let [acc (find-personal-account-by-name chat-data virtual-personal-account-name)]
                             (is (not (:revoked acc)) "the selected account is reinstated"))

                           ;; bot responses
                           (is (= 2 (get-total-responses)) "total responses")
                           (is (do-responses-match?
                                 (fn [res]
                                   ;; TODO: This checks group may be extracted into fn.
                                   (and (= chat-id (-> res :chat :id))
                                        (= settings-msg-id (:message_id res))
                                        (some? (:reply_markup res))
                                        (= ["<accounts>" "<expense_items>" "<shares>"]
                                           (mapv :callback_data (get-inline-kbd-row res 0)))))
                                 (fn [res]
                                   (= chat-id (-> res :chat :id))))
                               "responses match all predicates")))))

                  (let [[_ _ [accounts-mgmt-msg]] (enter-accounts-mgmt! (generate-callback-query-id))]
                    (testing ":: Should notify user when there's no eligible accounts"
                      (with-mock-send
                        #(let [callback-query-id (generate-callback-query-id)
                               update (build-update :callback_query
                                                    {:id callback-query-id
                                                     :chat {:id chat-id :title chat-title :type "group"}
                                                     :from {:id user-id :first_name user-name :language_code "ru"}
                                                     :message accounts-mgmt-msg
                                                     :data "<accounts/reinstate>"})
                               result (bot-api update)
                               chat-data (get-chat-data chat-id)]
                           ;; operation code / immediate response
                           (is (= {:method "answerCallbackQuery"
                                   :callback_query_id callback-query-id} result))

                           ;; chat data state
                           (is (= :ready (get-chat-state chat-data)) "chat state")

                           ;; bot responses
                           (is (= 1 (get-total-responses)) "total responses")
                           (is (= true (get-response 1))))))))))))))))
