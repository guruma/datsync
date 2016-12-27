(ns dat.remote.impl.sente
  #?(:cljs (:require-macros [cljs.core.async.macros :as async-macros :refer [go go-loop]]))
  (:require #?@(:clj [[clojure.core.async :as async :refer [go go-loop]]]
                :cljs [[cljs.core.async :as async]])
            [dat.sync.client :as sync]
            [dat.reactor :as reactor]
            [dat.sync.utils :as utils]
            [dat.spec.protocols :as protocols]
            [com.stuartsierra.component :as component]
            [cognitect.transit] ;; undeclared dependency of sente
            [taoensso.timbre :as log #?@(:cljs [:include-macros true])]
            [taoensso.sente :as sente]
            [taoensso.sente.packers.transit :as sente-transit]))


;; ## Implement the comms protocols using Sente


#?(:cljs
    (do
      ;; This is a hack to get the db/fn objects to not break on data load
      (defrecord DBFn [lang params code])
      ;(defn tagged-fn [:datsync.server/db-fn])
      (cljs.reader/register-tag-parser! 'db/fn pr-str)))


(defrecord SenteRemote [chsk ch-recv out-chan send-fn state open?]
  component/Lifecycle
  (start [component]
    (log/info "Starting SenteRemote Component")
    (let [out-chan (or out-chan (async/chan 100))
          packer (sente-transit/get-flexi-packer :edn)
          sente-fns (sente/make-channel-socket! "/chsk" {:type :auto :packer packer})
          ch-recv (:ch-recv sente-fns)]
      ;; Set Sente to pipe it's events such that they all (at the top level) fit the standard re-frame shape
      (async/pipeline 1 out-chan (map (fn [x] [::event (:event x)])) ch-recv)
      ;; Return component with state assoc'd in
      (merge component
             sente-fns
             {:out-chan out-chan
              :open? (atom false)})))
  (stop [component]
    (log/info "Stopping SenteRemote component")
    (try
      ;(when out-chan (async/close! out-chan))
      ;(when ch-recv (async/close! ch-recv))
      (log/debug "SenteRemote stopped successfully")
      (assoc component :ch-recv nil :out-chan nil)
      (catch #?(:clj Exception :cljs :default) e
        (log/error "Error stopping SenteRemote:" e)
        component)))
  protocols/PRemoteSendEvent
  (send-event! [component event]
    (send-fn event))
  protocols/PRemoteEventChan
  (remote-event-chan [component]
    out-chan))

(defn new-sente-remote []
  (map->SenteRemote {}))


;; ## Install handler hooks; Note that the component in question here is not the remote, but the app

;; Event handlers should be created and installed as data:


(reactor/register-handler
  ::event
  (fn [app db [_ sente-message]]
    (log/debug "Sente message recieved:" (first sente-message))
    (reactor/resolve-to app db [sente-message])))

(reactor/register-handler
  :chsk/state
  (fn [app db [_ {:as ev-msg :keys [?data]}]]
    (try
      (if (:first-open? ev-msg)
        (reactor/with-effect [:dat.remote/send-event! [:dat.sync.client/bootstrap nil]]
          db)
        db)
      (catch #?(:clj Exception :cljs :default) e
        (log/error "Exception handling :chsk/state:" e)))))

(reactor/register-handler
  :chsk/handshake
  ;(fn [app db {:as ev-msg :keys [?data]}]
  (fn [app db [_ {:as ev-msg :keys [?data]}]]
    (log/warn "You should probably write something here! This is a no-op.")
    (log/debug "Calling :chsk/handshake with:" ev-msg)
    ;; This is just to deal with how sente organizes things on it's chans; If we wanted though, we could
    ;; manually track things here
    db))

(reactor/register-handler
  :chsk/recv
  ;(fn [app db {:as ev-msg :keys [?data]}]
  (fn [app db [_ event]]
    ;; This is just to deal with how sente organizes things on it's chans; If we wanted though, we could
    ;; manually track things here
    (log/info ":chsk/recv for event-id:" (first event))
    (reactor/resolve-to app db
      [event])))


