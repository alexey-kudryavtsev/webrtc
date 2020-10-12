(ns tyrell.webrtc.front
  (:require [clojure.core.async :as async]
            [clojure.edn :as edn]
            [cljs.core.async.interop :refer [<p!]]
            ["react" :as react]
            ["react-dom" :as react-dom]))

(defn e
  [el props & children]
  (apply react/createElement el (clj->js props) children))

(defn set-stream [stream]
  (fn [ref]
    (when (some? ref)
      (set! (.-srcObject ref) stream))))

(defn hook-up-peer-conn [to from peer-conn ws]
  (prn "hooking up peer connection for ice candidates")
  (set! (.-onicecandidate peer-conn) (fn [e]
                                       (prn "new icecandidate")
                                       (when-let [candidate (.-candidate e)]
                                         (.send ws {:ice-candidate candidate :for to :from from}))))
  (set! (.-onconnectionstatechange peer-conn) (fn [e]
                                               (prn "connection state changed" e (.-connectionState peer-conn))
                                               (when (= "connected" (.-connectionState peer-conn))
                                                 (prn "Connection established!")))))

(defn init-call [to from ws peer-conn]
  (async/go (let [offer (<p! (.createOffer peer-conn))]
              (<p! (.setLocalDescription peer-conn offer))
              (hook-up-peer-conn to from peer-conn ws)
              (.send ws {:offer (js/JSON.stringify offer) :for to :from from}))))

(defn make-ws [user-id peer-conn set-incoming-call]
  (prn "creating websocket")
  (let [ws (js/WebSocket. "ws://localhost:8080/ws")]
    (set! (.-onmessage ws) (fn [e]
                             (let [data (edn/read-string (.-data e))]
                               (cond (:answer data) (do (prn "got answer" data)
                                                        (let [answer (js/JSON.parse (:answer data))]
                                                          (.setRemoteDescription peer-conn (js/RTCSessionDescription. answer))))
                                     (:offer data) (do (prn "got offer" data)
                                                       (async/go (let [offer (js/JSON.parse (:offer data))
                                                                       _ (.setRemoteDescription peer-conn (js/RTCSessionDescription. offer))
                                                                       answer (<p! (.createAnswer peer-conn))]
                                                                   (<p! (.setLocalDescription peer-conn answer))
                                                                   (set-incoming-call (:from data))
                                                                   (.send ws {:answer (js/JSON.stringify answer) :for (:from data) :from user-id}))))
                                     (:ice-candidate data) (do (prn "got ice candidate")
                                                               (async/go (try (<p! (.addIceCandidate peer-conn (js/JSON.parse (:ice-candidate data))))
                                                                              (catch :default _
                                                                                (prn "Bad candidate")))))
                                     (= data 0) (prn "Success")
                                     :else (prn "unknown message " (.-data e))))))
    (set! (.-onopen ws) (fn [_]
                          (.send ws {:user-id user-id})))
    ws))

(defn view [props]
  (let [{:strs [stream user-id peer-conn]} (js->clj props)
        [state set-state] (react/useState "")
        [incoming-call set-incoming-call] (react/useState nil)
        [ws set-ws] (react/useState nil)]
    (react/useEffect (fn [] (set-ws (make-ws user-id peer-conn set-incoming-call))) #js[])
    (react/useEffect (fn []
                       (when incoming-call
                         (hook-up-peer-conn incoming-call user-id peer-conn ws))
                       js/undefined))
    (e "div" nil
       (e "h1" nil "WebRTC Demo")
       (e "p" nil (str "User id: " user-id))
       (when incoming-call (e "p" nil (str "Incoming call from " incoming-call)))
       (e "span" nil (e "label" nil "connect to ")
          (e "input" (clj->js {:value state :onChange #(-> % .-target .-value set-state)}))
          (e "button" (clj->js {:onClick #(init-call (js/parseInt state) user-id ws peer-conn)}) "connect"))
       (e "p" nil nil)
       (e "div" nil (e "video" {:ref (set-stream stream) :id "video" :autoPlay true} nil)))))

(defn ^:export init []
  (async/go
    (let [user-id (rand-int 100000)
          stream (<p! (js/window.navigator.mediaDevices.getUserMedia #js{"video" true}))
          peer-conn (js/RTCPeerConnection. (clj->js {"iceServers" [{"urls" "stun:stun.l.google.com:19302"}]
                                                     "iceTransportPolicy" "all"
                                                     "iceCandidatePoolSize" "0"}))]
      (react-dom/render (e view {:stream stream :user-id user-id :peer-conn peer-conn})
                        (js/document.getElementById "root")))))