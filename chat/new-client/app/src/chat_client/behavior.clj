(ns ^:shared chat-client.behavior
    (:require [clojure.string :as string]
              [io.pedestal.app :as app]
              [io.pedestal.app.dataflow :as d]
              [io.pedestal.app.util.platform :as platform]
              [io.pedestal.app.util.log :as log]
              [io.pedestal.app.messages :as msg]
              [clojure.set :as set]
              [chat-client.hook :as hook]
              [chat-client.util :as util]))

;; Transforms
(defn nickname-transform
  [_ message]
  (:nickname message))

(defn send-message
  [old-value message]
  (let [msg {:id (util/random-id)
             :time (platform/date)
             :nickname (:nickname message)
             :text (:text message)
             :status :pending}]
    (-> old-value
        (update-in [:sent] conj msg)
        (assoc :sending msg))))

(defn clear-outbound-messages
  [old-value _]
  (log/debug :in :clear-messages :transform :outbound)
  (-> old-value
      (assoc :sent [])
      (dissoc :sending)))

(defn clear-inbound-messages
  [old-value _]
  (log/debug :in :clear-messages :transform :inbound)
  (assoc old-value :received []))

(defn receive-inbound
  [old-value message]
  (let [msg {:id (:id message) :time (platform/date)
             :nickname (:nickname message) :text (:text message)}]
    (update-in old-value [:received] conj msg)))

;; Derives

;; TODO - is there a need to diff against old if inputs give us new?
(defn new-messages [_ inputs]
  (sort-by :time (get-in inputs [:inbound :received])))

(defn- index-by [coll key]
  (reduce
   (fn [a x]
     (assoc a (key x) x))
   {}
   coll))

(defn- updated-message? [reference-messages msg]
  (when-let [reference-msg (reference-messages (:id msg))]
    (not= (:time reference-msg) (:time msg))))

(defn updated-messages [state inputs]
  (let [new-msgs (:new-messages inputs)
        out-msgs-index (index-by (get-in inputs [:outbound :sent]) :id)]
    (let [updated-msgs (filter (partial updated-message? out-msgs-index) new-msgs)]
      (map #(assoc % :status :confirmed) updated-msgs))))

;; Effect
(defn send-message-to-server [outbound]
  [{msg/topic [:server] :out-message (:sending outbound)}])

;; Emits
(defn init-app-model [_]
  [{:chat
    {:log {}
     :form
     {:transforms
      {:clear-messages [{msg/topic [:outbound]} {msg/topic [:inbound]}]
       :set-nickname [{msg/topic [:nickname] (msg/param :nickname) {}}]}}}}])

(defn- new-deltas [value]
  (vec (mapcat (fn [{:keys [id] :as msg}]
                 [[:node-create [:chat :log id] :map]
                  [:value [:chat :log id] msg]])
               value)))

(defn set-nickname-deltas
  [nickname]
  [[:node-create [:chat :nickname] :map]
   [:value [:chat :nickname] nickname]
   [:transform-enable [:chat :form] :clear-nickname [{msg/topic [:nickname]}]]
   [:transform-enable [:chat :form] :send-message [{msg/topic [:outbound]
                                                    (msg/param :text) {}
                                                    :nickname nickname}]]
   [:transform-disable [:chat :form] :set-nickname]])

(def clear-nickname-deltas
  [[:node-destroy [:chat :nickname]]
   [:transform-disable [:chat :form] :clear-nickname]
   [:transform-disable [:chat :form] :send-message]
   [:transform-enable [:chat :form] :set-nickname [{msg/topic [:nickname]
                                                    (msg/param :nickname) {}}]]])
(defn- nickname-deltas [nickname]
  (if nickname (set-nickname-deltas nickname) clear-nickname-deltas))

(def sort-order
  {[:new-messages] 0
   [:deleted-messages] 1
   [:updated-messages] 2})

(defn chat-emit
  [inputs]
  (reduce (fn [a [input-path new-value]]
            (concat a (case input-path
                        ;; TODO - enable these
                        [:new-messages] (new-deltas new-value)
                        ;;:deleted-messages (delete-deltas new-value)
                        ;;:updated-messages (update-deltas new-value)
                        [:nickname] (nickname-deltas new-value)
                        [])))
          []
          (sort-by #(get sort-order (key %)) (d/added-inputs inputs))))

(def example-app
  {:version 2
   :transform [[:set-nickname [:nickname] nickname-transform]
               [:clear-nickname [:nickname] (constantly nil)]
               [:received [:inbound] receive-inbound]
               [:clear-messages [:inbound] clear-inbound-messages]
               [:send-message [:outbound] send-message]
               [:clear-messages [:outbound] clear-outbound-messages]]
   :derive #{[{[:inbound] :inbound [:outbound] :outbound} [:new-messages] new-messages :map]
             [{[:new-messages] :new-messages [:outbound] :outbound} [:updated-messages] updated-messages :map]}
   :effect #{[#{[:outbound]} send-message-to-server :single-val]}
   :emit [{:init init-app-model}
          [#{[:nickname] [:new-messages]} chat-emit]
          ;[#{[:*]} (app/default-emitter [])]
          ]})


(defn log-fn [msg f]
  (fn [& args]
    (print msg)
    (prn args)
    (apply f args)))

(hook/log-app #'example-app log-fn)