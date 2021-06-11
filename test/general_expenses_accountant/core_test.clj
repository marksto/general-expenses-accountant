(ns general-expenses-accountant.core-test
  (:refer-clojure :exclude [reduce])
  (:require [clojure.test :refer [use-fixtures deftest testing is]]
            [clojure.core.async :refer [go chan >! <!! close! reduce]]
            [clojure.string :as str]
            [clojure.zip :as zip]

            [taoensso.encore :as encore :refer [defalias]]
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

(defn consists-exactly-of?
  [res key text-vals]
  ;; NB: Asserts a linear response structure, which is fine for now.
  (or (= text-vals (mapv key (get-inline-kbd-row res 0)))
      (= text-vals (mapv key (get-inline-kbd-col res 0)))))

(defn contains-all?
  [res key text-vals]
  ;; NB: Asserts a linear response structure, which is fine for now.
  (or (every? (set (map key (get-inline-kbd-row res 0))) text-vals)
      (every? (set (map key (get-inline-kbd-col res 0))) text-vals)))

(defn not-contains?
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

(def collect-responses (constantly []))

(defn- get-total-responses
  []
  (count (collect-responses)))

(defn- get-response
  [n]
  {:pre [(pos-int? n)]}
  (nth (collect-responses) (dec n)))

(defn- do-responses-match?*
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

(defn- do-responses-match?
  [& resp-assert-preds]
  (apply do-responses-match?* (collect-responses) resp-assert-preds))

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

(defn valid-settings-msg?
  [res chat-id settings-msg-id]
  (and (= chat-id (-> res :chat :id))
       (= settings-msg-id (:message_id res))
       (some? (:reply_markup res))
       (consists-exactly-of? res :callback_data
                             ["<accounts>" "<expense_items>" "<shares>"])))

; Test Cases

;; TODO: Get rid of '::' prefix in names. Change all ':name' keys to ':test'?

(def start-new-chat-test-group
  {:group ":: 1 Start a new chat"
   :tests [{:name ":: 1-1 Create chat with bot"
            :tags [:new-group-chat :single-user]
            :give [:name-request-msg]}
           {:name ":: 1-2 Reply to the name request message"
            :tags [:new-group-chat :single-user]
            :take [:name-request-msg]
            :give [:settings-msg]}]})

(def enter-the-accounts-menu
  {:name ":: Enter the 'Accounts' menu"
   :tags [:menu-navigation]
   :take [:settings-msg]
   :give [:accounts-mgmt-msg]})

(def create-account-test-group
  {:group ":: 2-1-1 Create a new account"
   :tests [{:name ":: Should prompt the user to select the type of a new account"
            :tags [:create-account]
            :take [:accounts-mgmt-msg]
            :give [:create-account-msg]}
           {:name ":: Should restore the settings message & prompt the user for an account name"
            :tags [:create-account]
            :take [:create-account-msg]
            :give [:new-account-name-request-msg]}
           {:name ":: Should create a new account of the selected type and with the specified name"
            :tags [:create-account]
            :take [:new-account-name-request-msg]
            :give [:settings-msg :created-account]}]})

(def create-virtual-personal-account-test-group
  (assoc create-account-test-group
    :binds {:account-type "at::personal"
            :account-name :virtual-personal-account-name}))

(def rename-account-test-group
  {:group ":: 2-1-2 Rename an account"
   :tests [{:name ":: Should prompt the user to select an account to rename"
            :tags [:rename-account]
            :take [:accounts-mgmt-msg]
            :give [:account-to-rename-selection-msg]}
           {:name ":: Should restore the settings message & prompt the user for an account name"
            :tags [:rename-account]
            :take [:account-to-rename-selection-msg]
            :give [:account-rename-request-msg]}
           {:name ":: Should update the selected account with the specified name"
            :tags [:rename-account]
            :take [:account-rename-request-msg]}]})

(def rename-user-personal-account-test-group
  (assoc rename-account-test-group
    :binds {:account-to-rename-cd :user-personal-account-cd
            :account-to-rename-old-name :user-personal-account-name
            :account-to-rename-new-name (generate-name-str "Acc/")}))

(def rename-virtual-personal-account-test-group
  (assoc rename-account-test-group
    :binds {:account-to-rename-cd :virtual-personal-account-cd
            :account-to-rename-old-name :virtual-personal-account-name
            :account-to-rename-new-name (generate-name-str "Acc/")}))

;; NB: To be run twice: 1. when there are no accs; 2. after revoking an acc.
(def no-eligible-accounts-for-revocation
  {:name ":: Should notify user when there's no eligible accounts for revocation"
   :tags [:revoke-account :notifications]
   :take [:accounts-mgmt-msg]})

(def revoke-account-test-group
  {:group ":: 2-1-3 Revoke an account"
   :tests [(update enter-the-accounts-menu :take conj :created-account)
           {:name ":: Should prompt the user to select an account to revoke"
            :tags [:revoke-account]
            :bind {:contains-all [:virtual-personal-account-name]
                   :not-contains [:user-personal-account-name]}
            :take [:accounts-mgmt-msg]
            :give [:account-to-revoke-selection-msg]}
           {:name ":: Should restore the settings message & mark the selected account as revoked"
            :tags [:revoke-account]
            :bind {:account-to-revoke-cd :virtual-personal-account-cd
                   :account-to-revoke-name :virtual-personal-account-name}
            :take [:account-to-revoke-selection-msg]
            :give [:settings-msg :revoked-account]}
           no-eligible-accounts-for-revocation]})

;; NB: To be run twice: 1. when there are no accs; 2. after reinstating an acc.
(def no-eligible-accounts-for-reinstatement
  {:name ":: Should notify user when there's no eligible accounts for reinstatement"
   :tags [:reinstate-account :notifications]
   :take [:accounts-mgmt-msg]})

(def reinstate-account-test-group
  {:group ":: 2-1-4 Reinstate an account"
   :tests [(update enter-the-accounts-menu :take conj :revoked-account)
           {:name ":: Should prompt the user to select an account to reinstate"
            :tags [:reinstate-account]
            :bind {:contains-all [:virtual-personal-account-name]
                   :not-contains [:user-personal-account-name]}
            :take [:accounts-mgmt-msg]
            :give [:account-to-reinstate-selection-msg]}
           {:name ":: Should restore the settings message & reinstate the selected account"
            :tags [:reinstate-account]
            :bind {:account-to-reinstate-cd :virtual-personal-account-cd
                   :account-to-reinstate-name :virtual-personal-account-name}
            :take [:account-to-reinstate-selection-msg]}
           no-eligible-accounts-for-reinstatement]})

(def accounts-mgmt-test-group
  {:group ":: 2-1 Accounts Management"
   :binds {:virtual-personal-account-name (generate-name-str "Acc/")
           :virtual-personal-account-cd "ac::personal::1"}
   :tests [enter-the-accounts-menu

           #{no-eligible-accounts-for-revocation
             no-eligible-accounts-for-reinstatement}
           #{[create-virtual-personal-account-test-group
              #{[revoke-account-test-group
                 reinstate-account-test-group]
                rename-virtual-personal-account-test-group}]
             rename-user-personal-account-test-group

             {:name ":: Exit the 'Accounts' menu"
              :tags [:menu-navigation]
              :take [:accounts-mgmt-msg]}}]})

(def settings-test-group
  {:group ":: 2 Settings"
   :tests [accounts-mgmt-test-group]})

(def use-cases
  [{:group "Use Case 1. Personal accounting (single user)"
    :binds {:user-id (generate-user-id)
            :user-name (generate-name-str "User/")}
    :tests [{:group ":: Group Chat"
             :binds {:chat-id (generate-chat-id)
                     :chat-title (generate-name-str 3 "Group/")
                     :user-personal-account-name (generate-name-str "Acc/")
                     :user-personal-account-cd "ac::personal::0"}
             :tests [start-new-chat-test-group
                     settings-test-group]}]}])

; Tests Composition

(defn- to-zipper
  [test-groups]
  (zip/zipper #(or (vector? %) (:tests %) (set? %))
              #(if (or (vector? %) (set? %)) (seq %) (:tests %))
              (fn [node children] (with-meta children (meta node)))
              test-groups))

;; TODO: Build up the full TC name to be used as the 'testing' 1st arg.

(defn- traverse
  ;; TODO: Write a comprehensive doc string.
  ([loc]
   (traverse loc {} []))
  ([loc ctx tests]
   (letfn [(subs-from-ctx [binds]
             (into {} (map #(if (keyword? (val %))
                              (assoc % 1 (get ctx (val %)))
                              %)
                           binds)))]
     (cond
       (or (nil? loc) (zip/end? loc))
       tests

       (or (vector? (zip/node loc))
           (set? (zip/node loc)))
       (recur (zip/next loc) ctx tests)

       (:group (zip/node loc))
       (let [tg-zipper (to-zipper (:tests (zip/node loc)))
             binds (subs-from-ctx (:binds (zip/node loc)))
             tg-tests (traverse tg-zipper (merge ctx binds) [])]
         (recur (zip/right loc) ctx (into tests tg-tests)))

       (:name (zip/node loc))
       (let [bind (subs-from-ctx (:bind (zip/node loc)))
             case (dissoc (zip/node loc) :bind)
             test {:case case, :ctx ctx}]
         (recur (zip/next loc) (merge ctx bind) (conj tests test)))

       :else ;; stops the whole process
       (do
         (println "Stopped traversing!") ;; TODO: Re-write w/ an exception.
         (zip/node loc))))))

(defn unit-update-test
  "A unit test of the incoming update for a Telegram bot."
  [{{:keys [mock-fns type data-fn] :or {mock-fns {}}} :update
    checks :checks return-fn :return-fn :as _test-case} ctx]
  (let [[result responses] (with-mock-send
                             mock-fns
                             #(let [update (build-update type (data-fn ctx))
                                    result (bot-api update)]
                                [result (collect-responses)]))]
    ;; operation code / immediate response
    (when-let [exp-result (:result checks)]
      (is (= exp-result result)))

    ;; chat data state
    (encore/when-let [exp-chat-data (:chat-data checks)
                      chat-data (get-chat-data (:chat-id ctx))]
      ;; TODO: Add other possible validations here as well.
      (when (some? (:members-count exp-chat-data))
        (is (= (:members-count exp-chat-data) (get-members-count chat-data))
            "members count"))
      (when (some? (:chat-state exp-chat-data))
        (is (= (:chat-state exp-chat-data) (get-chat-state chat-data))
            "chat state")))

    ;; bot responses
    (encore/when-let [exp-responses (:responses checks)
                      resp-assert-preds-fn (or (:assert-preds-fn exp-responses) :none)]
      (when (some? (:total exp-responses))
        (is (= (:total exp-responses) (count responses))
            "total responses"))
      (when (fn? resp-assert-preds-fn)
        (is (apply do-responses-match?* responses (resp-assert-preds-fn ctx))
            "responses match all predicates")))

    (when (some? return-fn)
      (return-fn ctx result responses))))

(defn comp-update-test
  "A composite test of the incoming update for a Telegram bot."
  [test-groups]
  (let [tests (traverse (to-zipper test-groups))]
    (doseq [{:keys [case ctx]} tests]
      (testing (:name case)
        (unit-update-test case ctx)))))

(deftest user-scenarios
  (comp-update-test use-cases))



;; TODO: Remove later, when all test fns are transformed into plain data.
(comment
  (deftest personal-accounting
    (testing "Use Case 1. Personal accounting (single user)"

      (testing ":: Group Chat"
        (let [chat-id (generate-chat-id)
              chat-title (generate-name-str 3 "Group/")
              user-id (generate-user-id)
              user-name (generate-name-str "User/")

              settings-msg (atom nil)]

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
                                        (consists-exactly-of? res :callback_data
                                                              ["<accounts>" "<expense_items>" "<shares>"])
                                        (= [:settings :initial]
                                           (get-bot-msg-state chat-data (:message_id res))))
                               (reset! settings-msg res)
                               true)))
                         "responses match all predicates"))))))

          (testing ":: 2 Settings"
            (let [get-settings-msg-state (fn [chat-data]
                                           (get-bot-msg-state chat-data (:message_id @settings-msg)))
                  re-enter-settings-msg! (fn []
                                           (with-mock-send
                                             #(let [chat-data (get-chat-data chat-id)]
                                                (when-not (= [:settings :initial] (get-settings-msg-state chat-data))
                                                  (let [update (build-update :message
                                                                             {:message_id (generate-message-id)
                                                                              :chat {:id chat-id :title chat-title :type "group"}
                                                                              :from {:id user-id :first_name user-name :language_code "ru"}
                                                                              :date (get-datetime-in-tg-format)
                                                                              :text "/settings"
                                                                              :entities [{:offset 0 :length 9 :type "bot_command"}]})]
                                                    (bot-api update)
                                                    (reset! settings-msg (get-response 1)))))))]

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

                      user-personal-account-cd "ac::personal::0"

                      virtual-personal-account-name (generate-name-str "Acc/")
                      virtual-personal-account-cd "ac::personal::1"]

                  (testing ":: 2-1-0 Menu navigation"
                    (re-enter-settings-msg!)
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
                               (get-settings-msg-state chat-data)) "msg state")

                        ;; bot responses
                        (is (= 0 (count rest)) "total responses")
                        (let [res accounts-mgmt-msg]
                          (is (= chat-id (-> res :chat :id)))
                          (is (str/includes? (:text res) user-personal-account-name))
                          (is (some? (:reply_markup res)))
                          (is (contains-all? res :callback_data
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
                                    (get-settings-msg-state chat-data)) "msg state")

                             ;; bot responses
                             (is (= 1 (get-total-responses)) "total responses")
                             (is (do-responses-match?
                                   (fn [res]
                                     (valid-settings-msg? res chat-id (:message_id @settings-msg))))
                                 "responses match all predicates"))))))

                  (testing ":: 2-1-1 Create a new account"
                    (re-enter-settings-msg!)
                    (let [[_ _ [accounts-mgmt-msg]] (enter-accounts-mgmt! (generate-callback-query-id))

                          create-account-msg (promise)
                          new-account-name-request-msg (promise)

                          account-type "at::personal"
                          account-name virtual-personal-account-name]

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
                                    (get-settings-msg-state chat-data)) "msg state")

                             ;; bot responses
                             (is (= 1 (get-total-responses)) "total responses")
                             (is (do-responses-match?
                                   (fn [res]
                                     (when (and (= chat-id (-> res :chat :id))
                                                (some? (:reply_markup res))
                                                (contains-all? res :callback_data ["at::group" "at::personal"]))
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
                                                       :data account-type})
                                 result (bot-api update)
                                 chat-data (get-chat-data chat-id)]
                             ;; operation code / immediate response
                             (is (= {:method "answerCallbackQuery"
                                     :callback_query_id callback-query-id} result))

                             ;; chat data state
                             (is (= :waiting (get-chat-state chat-data)) "chat state")
                             (is (= [:settings :initial]
                                    (get-settings-msg-state chat-data)) "msg state")

                             ;; bot responses
                             (is (= 2 (get-total-responses)) "total responses")
                             (is (do-responses-match?
                                   (fn [res]
                                     (valid-settings-msg? res chat-id (:message_id @settings-msg)))
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
                                                       :text account-name})
                                 result (bot-api update)
                                 chat-data (get-chat-data chat-id)]
                             ;; operation code / immediate response
                             (is (= op-succeed result))

                             ;; chat data state
                             (is (= :ready (get-chat-state chat-data)) "chat state")
                             (let [new-acc (find-personal-account-by-name chat-data account-name)]
                               (is (some? new-acc) "a personal account with the specified name exists"))

                             ;; bot responses
                             (is (= 1 (get-total-responses)) "total responses")
                             (is (= chat-id (-> (get-response 1) :chat :id))))))))

                  (testing ":: 2-1-2 Rename an account"
                    (re-enter-settings-msg!)
                    (let [[_ chat-data [accounts-mgmt-msg]] (enter-accounts-mgmt! (generate-callback-query-id))

                          account-to-rename-selection-msg (promise)
                          account-rename-request-msg (promise)

                          account-to-rename-cd user-personal-account-cd
                          account-to-rename-old-name (:name (get-personal-account chat-data {:user-id user-id}))
                          account-to-rename-new-name (generate-name-str "Acc/")]

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
                                 chat-data (get-chat-data chat-id)]
                             ;; operation code / immediate response
                             (is (= {:method "answerCallbackQuery"
                                     :callback_query_id callback-query-id} result))

                             ;; chat data state
                             (is (= :ready (get-chat-state chat-data)) "chat state")
                             (is (= [:settings :account-renaming]
                                    (get-settings-msg-state chat-data)) "msg state")

                             ;; bot responses
                             (is (= 1 (get-total-responses)) "total responses")
                             (is (do-responses-match?
                                   (fn [res]
                                     (when (and (= chat-id (-> res :chat :id))
                                                (some? (:reply_markup res))
                                                ;; TODO: Should test for the 'virtual-personal-account-name'
                                                ;;       separately, upon its creation. Call this test with
                                                ;;       other params bound.
                                                (contains-all? res :text [account-to-rename-old-name]))
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
                                                       :data account-to-rename-cd})
                                 result (bot-api update)
                                 chat-data (get-chat-data chat-id)]
                             ;; operation code / immediate response
                             (is (= {:method "answerCallbackQuery"
                                     :callback_query_id callback-query-id} result))

                             ;; chat data state
                             (is (= :waiting (get-chat-state chat-data)) "chat state")
                             (is (= [:settings :initial]
                                    (get-settings-msg-state chat-data)) "msg state")

                             ;; bot responses
                             (is (= 2 (get-total-responses)) "total responses")
                             (is (do-responses-match?
                                   (fn [res]
                                     (valid-settings-msg? res chat-id (:message_id @settings-msg)))
                                   (fn [res]
                                     (when (and (= chat-id (-> res :chat :id))
                                                (some? (:reply_markup res))
                                                (-> res :reply_markup :force_reply)
                                                (-> res :reply_markup :selective)
                                                (str/includes? (:text res) user-name)
                                                (str/includes? (:text res) account-to-rename-old-name)
                                                (= (get-bot-msg-id chat-id [:to-user user-id :request-rename-msg-id])
                                                   (:message_id res)))
                                       (deliver account-rename-request-msg res)
                                       true)))
                                 "responses match all predicates"))))

                      ;; TODO: Add a TC that checks that other interactions are ineffectual at this point.

                      (testing ":: Should update the selected account with the specified name"
                        (with-mock-send
                          #(let [update (build-update :message
                                                      {:message_id (generate-message-id)
                                                       :chat {:id chat-id :title chat-title :type "group"}
                                                       :from {:id user-id :first_name user-name :language_code "ru"}
                                                       :date (get-datetime-in-tg-format)
                                                       :reply_to_message @account-rename-request-msg
                                                       :text account-to-rename-new-name})
                                 result (bot-api update)
                                 chat-data (get-chat-data chat-id)]
                             ;; operation code / immediate response
                             (is (= op-succeed result))

                             ;; chat data state
                             (is (= :ready (get-chat-state chat-data)) "chat state")
                             (let [new-acc (find-personal-account-by-name chat-data account-to-rename-new-name)]
                               (is (some? new-acc) "a personal account with the specified name exists"))

                             ;; bot responses
                             (is (= 1 (get-total-responses)) "total responses")
                             (is (= chat-id (-> (get-response 1) :chat :id))))))))

                  (testing ":: 2-1-3 Revoke an account"
                    (re-enter-settings-msg!)
                    (let [[_ _ [accounts-mgmt-msg]] (enter-accounts-mgmt! (generate-callback-query-id))

                          account-to-revoke-selection-msg (promise)
                          account-to-revoke-cd virtual-personal-account-cd
                          account-to-revoke-name virtual-personal-account-name]

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

                                 ;; NB: User cannot revoke or reinstate their own account.
                                 user-personal-account-name (:name (get-personal-account chat-data {:user-id user-id}))]
                             ;; operation code / immediate response
                             (is (= {:method "answerCallbackQuery"
                                     :callback_query_id callback-query-id} result))

                             ;; chat data state
                             (is (= :ready (get-chat-state chat-data)) "chat state")
                             (is (= [:settings :account-revocation]
                                    (get-settings-msg-state chat-data)) "msg state")

                             ;; bot responses
                             (is (= 1 (get-total-responses)) "total responses")
                             (is (do-responses-match?
                                   (fn [res]
                                     (when (and (= chat-id (-> res :chat :id))
                                                (some? (:reply_markup res))
                                                (contains-all? res :text [account-to-revoke-name])
                                                (not-contains? res :text [user-personal-account-name]))
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
                                    (get-settings-msg-state chat-data)) "msg state")
                             (let [acc (find-personal-account-by-name chat-data virtual-personal-account-name)]
                               (is (:revoked acc) "the selected account is marked as revoked"))

                             ;; bot responses
                             (is (= 2 (get-total-responses)) "total responses")
                             (is (do-responses-match?
                                   (fn [res]
                                     (valid-settings-msg? res chat-id (:message_id @settings-msg)))
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
                    (re-enter-settings-msg!)
                    (let [[_ _ [accounts-mgmt-msg]] (enter-accounts-mgmt! (generate-callback-query-id))

                          account-to-reinstate-selection-msg (promise)
                          account-to-reinstate-cd virtual-personal-account-cd
                          account-to-reinstate-name virtual-personal-account-name]

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

                                 ;; NB: User cannot revoke or reinstate their own account.
                                 user-personal-account-name (:name (get-personal-account chat-data {:user-id user-id}))]
                             ;; operation code / immediate response
                             (is (= {:method "answerCallbackQuery"
                                     :callback_query_id callback-query-id} result))

                             ;; chat data state
                             (is (= :ready (get-chat-state chat-data)) "chat state")
                             (is (= [:settings :account-reinstatement]
                                    (get-settings-msg-state chat-data)) "msg state")

                             ;; bot responses
                             (is (= 1 (get-total-responses)) "total responses")
                             (is (do-responses-match?
                                   (fn [res]
                                     (when (and (= chat-id (-> res :chat :id))
                                                (some? (:reply_markup res))
                                                (contains-all? res :text [account-to-reinstate-name])
                                                (not-contains? res :text [user-personal-account-name]))
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
                                    (get-settings-msg-state chat-data)) "msg state")
                             (let [acc (find-personal-account-by-name chat-data virtual-personal-account-name)]
                               (is (not (:revoked acc)) "the selected account is reinstated"))

                             ;; bot responses
                             (is (= 2 (get-total-responses)) "total responses")
                             (is (do-responses-match?
                                   (fn [res]
                                     (valid-settings-msg? res chat-id (:message_id @settings-msg)))
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
                             (is (= true (get-response 1)))))))))))))))))
