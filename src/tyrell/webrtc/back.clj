(ns tyrell.webrtc.back
  (:require [compojure.core :as c-core]
            [compojure.route :as c-route]
            [org.httpkit.server :as http]))

(defonce ws-clients (atom {}))

(defn relay [channel data]
  (let [data* (and (string? data) (read-string data))
        msg-for (:for data*)
        client (get @ws-clients msg-for)]
    (http/send! (:ch client) data)
    (http/send! channel "0")))

(defn ws [request]
  (http/with-channel request channel
    (http/on-receive channel (fn [data]
                               (let [data* (and (string? data) (read-string data))]
                                 (prn "processing message " data*)
                                 (when-let [user-id (:user-id data*)]
                                   (prn "registered client with id " user-id)
                                   (swap! ws-clients assoc user-id {:ch channel})
                                   (http/send! channel "0"))
                                 (when-let [offer (:offer data*)]
                                   (prn "sending offer for " (:for data*) " from " (:from data*)))
                                 (when-let [answer (:answer data*)]
                                   (prn "sending answer for " (:for data*) " from " (:from data*)))
                                 (when-let [ice-candidate (:ice-candidate data*)]
                                   (prn "sending ice candidate for " (:for data*) " from " (:from data*)))
                                 (and (or (:offer data*) (:answer data*) (:ice-candidate data*)) (relay channel data)))))))

(c-core/defroutes routes
  (c-core/GET "/ws" [] ws)     ;; websocket
  (c-route/files "/")
  (c-route/not-found "<p>Page not found.</p>"))

(defn -main []
  (http/run-server routes {:port 8080}))