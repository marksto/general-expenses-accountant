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

; Aliases (of required private fns)

(defalias get-chat-data core/get-chat-data)
(defalias update-chat-data! core/update-chat-data!)
(defalias get-members-count core/get-members-count)
(defalias get-chat-state core/get-chat-state)
(defalias get-bot-msg-id core/get-bot-msg-id)
(defalias set-bot-msg-id! core/set-bot-msg-id!)
(defalias get-bot-msg-state core/get-bot-msg-state)

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

; Mocks > DB

(def ^:private mock-db-queries
  {#'chats/select-all #(vector)
   #'chats/create! identity
   #'chats/update! identity
   #'tlogs/select-by-chat-id (fn [chat-id] {:id chat-id})
   #'tlogs/create! identity})

(defmacro with-mock-db
  [mock-fns test-func]
  `(with-redefs-fn
     (merge mock-db-queries ~mock-fns)
     ~test-func))

; Mocks > Bot API

(def collect-responses (constantly []))

(defn- get-total-responses
  []
  (count (collect-responses)))

(defn- get-response
  [n]
  {:pre [(pos-int? n)]}
  (nth (collect-responses) (dec n)))

(declare res) ;; just to prevent warnings in tests
(defmacro with-response
  [ns & forms]
  (let [body (for [n (if (coll? ns) ns [ns])]
               (let [msg (format "response #%s check" n)]
                 `(let [~'res (get-response ~n)]
                    ~@(map (fn [f] `(is ~f ~msg)) forms))))]
    (if (< 1 (count body))
      `(do ~@body)
      (first body))))

(defmacro with-mock-send
  [mock-fns test-func]
  `(let [resp-chan# (chan)
         mock-send# (fn [_# ids# response# opts#]
                      (let [tg-response# (to-tg-response ids# response# opts#)]
                        (go (>! resp-chan# (:result tg-response#)))
                        tg-response#))
         responses# (memoize (fn []
                               (close! resp-chan#)
                               (<!! (reduce conj [] resp-chan#))))]
     (with-mock-db
       (merge {#'core/send! mock-send#
               #'collect-responses responses#}
              ~mock-fns)
       ~test-func)))

; Test Cases

(deftest personal-accounting
  (testing "Use Case 1. Personal accounting (single user)"

    (testing ":: Group Chat"
      (let [chat-id (generate-chat-id)
            chat-title (generate-name-str 3 "Group/")
            user-id (generate-user-id)
            user-name (generate-name-str "User/")]

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
               (with-response [1 2]
                              (= chat-id (-> res :chat :id)))
               (with-response 1
                              (str/includes? (:text res) user-name))
               (with-response 2
                              (= (get-bot-msg-id chat-id :name-request-msg-id)
                                 (:message_id res))))))

        ;; TODO: Add a TC that checks that other interactions are ineffectual at this point. Join these TCs.

        (testing ":: 1-2 Reply to the name request message"
          (with-mock-send
            {#'core/can-write-to-user? (constantly true)} ;; as if user has already started some other chat
            #(let [message-id (generate-message-id)
                   personal-account-name (generate-name-str "Acc/")]
               (set-bot-msg-id! chat-id :name-request-msg-id message-id) ;; to be independent of test above
               (let [update (build-update :message
                                          {:message_id (generate-message-id)
                                           :chat {:id chat-id :title chat-title :type "group"}
                                           :from {:id user-id :first_name user-name :language_code "ru"}
                                           :date (get-datetime-in-tg-format)
                                           :reply_to_message {:message_id message-id}
                                           :text personal-account-name})
                     result (bot-api update)
                     chat-data (get-chat-data chat-id)]
                 ;; operation code / immediate response
                 (is (= op-succeed result))

                 ;; chat data state
                 (is (= 2 (get-members-count chat-data)) "members count")
                 (is (= :ready (get-chat-state chat-data)) "chat state")
                 (let [new-acc (find-personal-account-by-name chat-data personal-account-name)]
                   (is (some? new-acc) "an account with the specified name exists"))

                 ;; bot responses
                 (is (= 3 (get-total-responses)) "total responses")
                 (with-response 1
                                (= user-id (-> res :chat :id))
                                (str/includes? (:text res) chat-title))
                 (with-response [2 3]
                                (= chat-id (-> res :chat :id))
                                (some? (:reply_markup res)))
                 (with-response 2
                                (= "https://t.me/number_one_bot"
                                   (:url (get-inline-kbd-btn res 0 0))))
                 (with-response 3
                                (= ["<accounts>" "<expense_items>" "<shares>"]
                                   (mapv :callback_data (get-inline-kbd-row res 0)))
                                (= [:settings :initial]
                                   (get-bot-msg-state chat-data (:message_id res))))))))))))
