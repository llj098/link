(ns link.http
  (:refer-clojure :exclude [send])
  (:use [link core tcp])
  (:use [clojure.string :only [lower-case]])
  (:use [clojure.java.io :only [input-stream copy]])
  (:require [link.threads :as threads])
  (:require [clojure.core.async :as async
             :refer [<! >! <!! timeout chan alt! go close!]])
  (:import [java.io File InputStream PrintStream])
  (:import [java.net InetSocketAddress])
  (:import [io.netty.buffer
            ByteBuf
            Unpooled
            ByteBufInputStream
            ByteBufOutputStream])
  (:import [io.netty.handler.codec.http
            HttpVersion
            FullHttpRequest
            FullHttpResponse
            HttpHeaders
            HttpHeaders$Names
            HttpRequestDecoder
            HttpObjectAggregator
            HttpResponseEncoder
            HttpResponseStatus
            DefaultFullHttpResponse]))

(defn- as-map [headers]
  (apply hash-map
         (flatten (map #(vector (key %) (val %)) headers))))

(defn- find-query-string [^String uri]
  (if (< 0 (.indexOf uri "?"))
    (subs uri (+ 1 (.indexOf uri "?")))))

(defn- find-request-uri [^String uri]
  (if (< 0 (.indexOf uri "?"))
    (subs uri 0 (.indexOf uri "?"))
    uri))

(defn ring-request [ch req]
  (let [server-addr (channel-addr ch)
        uri (.getUri ^FullHttpRequest req)]
    {:server-addr (.getHostName ^InetSocketAddress server-addr)
     :server-port (.getPort ^InetSocketAddress server-addr)
     :remote-addr (.getHostName ^InetSocketAddress (remote-addr ch))
     :uri (find-request-uri uri)
     :query-string (find-query-string uri)
     :scheme :http
     :request-method (keyword (lower-case
                               (.. ^FullHttpRequest req getMethod name)))
     :content-type (HttpHeaders/getHeader
                    ^FullHttpRequest req HttpHeaders$Names/CONTENT_TYPE)
     :content-length (HttpHeaders/getContentLength req)
     :character-encoding (HttpHeaders/getHeader
                          req HttpHeaders$Names/CONTENT_ENCODING)
     :headers (as-map (.headers ^FullHttpRequest req))
     :body (let [cbis (ByteBufInputStream.
                       (.content ^FullHttpRequest req))]
             (if (> (.available ^ByteBufInputStream cbis) 0)
               cbis))}))

(defn ring-response [resp]
  (let [{status :status headers :headers body :body} resp
        status (or status 200)
        content (cond
                 (nil? body) (Unpooled/buffer 0)

                 (instance? String body)
                 (let [buffer (Unpooled/buffer)
                       bytes (.getBytes ^String body "UTF-8")]
                   (.writeBytes ^ByteBuf buffer ^bytes bytes)
                   buffer)

                 (sequential? body)
                 (let [buffer (Unpooled/buffer)
                       line-bytes (map #(.getBytes ^String % "UTF-8") body)]
                   (doseq [line line-bytes]
                     (.writeBytes ^ByteBuf buffer ^bytes line))
                   buffer)

                 (instance? File body)
                 (let [buffer (Unpooled/buffer)
                       buffer-out (ByteBufOutputStream. buffer)
                       file-in (input-stream body)]
                   (copy file-in buffer-out)
                   buffer)

                 (instance? InputStream body)
                 (let [buffer (Unpooled/buffer)
                       buffer-out (ByteBufOutputStream. buffer)]
                   (copy body buffer-out)
                   buffer))

        netty-response (DefaultFullHttpResponse.
                        HttpVersion/HTTP_1_1
                        (HttpResponseStatus/valueOf status)
                        content)

        netty-headers (.headers netty-response)]

    ;; write headers
    (doseq [header (or headers {})]
      (.set ^HttpHeaders netty-headers ^String (key header) ^Object (val header)))

    (.set ^HttpHeaders netty-headers
          ^String HttpHeaders$Names/CONTENT_LENGTH
          ^Object (.readableBytes content))

    netty-response))

(defn create-http-handler-from-ring [ring-fn debug async]
  (create-handler
   (on-message [ch msg]
               (let [req (ring-request ch msg)]
                 (if async
                   (go
                    (let [resp (<! (ring-fn req))]
                      (send ch (ring-response resp))))
                   (let [resp (ring-fn req)]
                     (send ch (ring-response resp))))))

   (on-error [ch exc]
             (let [resp-buf (Unpooled/buffer)
                   resp-out (ByteBufOutputStream. resp-buf)
                   resp (DefaultFullHttpResponse.
                         HttpVersion/HTTP_1_1
                         HttpResponseStatus/INTERNAL_SERVER_ERROR
                         resp-buf)]
               (if debug
                 (.printStackTrace exc (PrintStream. resp-out))
                 (.writeBytes resp-buf (.getBytes "Internal Error" "UTF-8")))

               (send ch resp)))))


(defn http-server [port ring-fn
                   & {:keys [threads executor debug host
                             ssl-context max-request-body async]
                      :or {threads 0
                           executor nil
                           debug false
                           host "0.0.0.0"
                           max-request-body 1048576
                           async false}}]
  (let [executor (if threads (threads/new-executor threads) executor)
        ring-handler (create-http-handler-from-ring ring-fn debug async)
        handlers [#(HttpRequestDecoder.)
                  #(HttpObjectAggregator. max-request-body)
                  #(HttpResponseEncoder.)
                  {:executor executor
                   :handler ring-handler}]]
    (tcp-server port handlers
                :host host
                :ssl-context ssl-context)))
