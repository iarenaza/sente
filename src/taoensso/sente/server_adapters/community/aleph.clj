(ns taoensso.sente.server-adapters.community.aleph
  {:author "Soren Macbeth (@sorenmacbeth)"}
  (:require
   [taoensso.sente.interfaces :as i]
   [clojure.string    :as str]
   [aleph.http        :as aleph]
   [manifold.stream   :as s]
   [manifold.deferred :as d]))

(extend-type manifold.stream.core.IEventSink
  i/IServerChan
  (sch-open?  [sch] (not (s/closed? sch)))
  (sch-close! [sch]       (s/close! sch))
  (sch-send!  [sch websocket? msg]
    (if (s/closed? sch)
      false
      (let [close-after-send? (if websocket? false true)]
        (s/put! sch msg)
        (when close-after-send? (s/close! sch))
        true))))

(defn- websocket-req? [ring-req]
  (when-let [s (get-in ring-req [:headers "upgrade"])]
    (= "websocket" (str/lower-case s))))

(deftype AlephAsyncNetworkChannelAdapter [opts]
  i/IServerChanAdapter
  (ring-req->server-ch-resp [sch-adapter ring-req callbacks-map]
    (let [{:keys [on-open on-close on-msg _on-error]} callbacks-map
          ws? (websocket-req? ring-req)]
      (if-let [s (and ws? (try @(aleph/websocket-connection ring-req opts)
                               (catch Exception e nil)))]
        (do
          (when on-msg   (s/consume     (fn [msg] (on-msg   s ws? msg)) s))
          (when on-close (s/on-closed s (fn []    (on-close s ws? nil))))
          (when on-open  (do                      (on-open  s ws?)))
          {:body s})

        (let [s (s/stream)] ; sch
          (when on-close (s/on-closed s (fn [] (on-close s ws? nil))))
          (when on-open  (do                   (on-open  s ws?)))
          {:body s})))))

(defn get-sch-adapter
  "Returns a Sente `ServerChan` adapter for `Aleph`,
  Ref. <https://github.com/clj-commons/aleph>.

  Supports websocket-connection options as in `aleph.http`.
  If no options map is provided, the default options apply."
  ([    ] (AlephAsyncNetworkChannelAdapter. nil))
  ([opts] (AlephAsyncNetworkChannelAdapter. opts)))
