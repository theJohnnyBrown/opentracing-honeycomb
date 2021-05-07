(ns opentracing-splash.tracing
  (:gen-class)
  (:require [clojure.string :as str]
            ;; [clojure.data.codec.base64 :as base64]
            [taoensso.timbre :as timbre])
  (:import (io.opentracing Span Tracer SpanContext Tracer$SpanBuilder ScopeManager Scope)
           (io.opentracing References)
           (io.opentracing.tag Tag)
           (io.opentracing.util ThreadLocalScopeManager ThreadLocalScope GlobalTracer)
           (java.util Map UUID Map$Entry)
           (java.util.logging Logger Level)
           (java.util Base64)))

(defn log-info [msg]
  (.log  (Logger/getLogger (str *ns*)) Level/INFO msg))

(defn encode-uuid [u]
  (let [encoder (Base64/getEncoder )]
    (->> u str (.getBytes) (.encode encoder) slurp)))

(defn decode-uuid [u]
  (let [decoder (Base64/getDecoder)]
    (->> (.getBytes u) (.decode decoder) slurp UUID/fromString)))

(defn now-micros ^long []
  (* (System/currentTimeMillis) 1000))

(defrecord SplashSpanContext [trace-id span-id parent-id baggage]
  SpanContext
  (toTraceId [this] (encode-uuid trace-id))
  (toSpanId [this] (encode-uuid span-id))
  (baggageItems [this] (.entrySet baggage)))

(defn send! [tracer span]
  (let [timestamp-ms (/ (.start-time-micros span) 1000)
        splash-span (merge (.-tags span)
                    {"name" (.-operation span)
                     "duration_ms" (/ (- (.-end-time-micros span) (.-start-time-micros span)) 1000)
                     "trace.span_id" (-> span .-context :span-id)
                     "trace.trace_id" (-> span .-context :trace-id)
                     "trace.parent_id" (-> span .-context :parent-id)})]
    (timbre/info splash-span)))

(deftype SplashSpan [operation context tags log-events start-time-micros end-time-micros tracer]
  Span
  (context [this] context)
  (^Span setTag [^Span this ^String k ^String v]
   (set!  (.-tags this) (assoc tags k v))
   this)
  (^Span setTag [^Span this ^String k ^boolean v]
   (set!  (.-tags this) (assoc tags k v))
   this)
  (^Span setTag [^Span this ^String k ^Number v]
   (set!  (.-tags this) (assoc tags k v))
   this)
  (^Span setTag [^Span this ^Tag k ^Object v]
   (set!  (.-tags this) (assoc tags k v))
   this)

  (^Span log [^Span this ^long timestamp-micros ^Map fields]
   (set! (.-log-events this)
         (conj log-events
               {::timestamp timestamp-micros ::fields fields}))
   this)
  (^Span log [^Span this ^Map fields]
   (.log this (now-micros) fields))
  (^Span log [^Span this ^String event]
   (.log this (now-micros) {:event event}))
  (^Span log [^Span this ^long timestamp-micros ^String event]
   (.log this timestamp-micros event {:event event}))

  (^Span setBaggageItem [^Span this ^String k ^String v]
   (set! (.-context this) (assoc-in context [:baggage k] v))
   this)
  (^String getBaggageItem [^Span this ^String k]
   (get-in context [:baggage k]))

  (^Span setOperationName [^Span this ^String op-name]
   (set! (.-operation this) op-name))

  (^void finish [^Span this ^long end-time]
   (do
     (set! (.-end-time-micros this) end-time)
     (send! tracer this)))
  (^void finish [^Span this]
   (.finish this (now-micros)))

  (toString [this] (pr-str
                    {:operation operation
                     :context context
                     :tags tags
                     :log-events log-events
                     :start-time-micros start-time-micros
                     :end-time-micros end-time-micros})))


(defn get-span
  ([operation] (get-span operation (->SplashSpanContext (UUID/randomUUID) (UUID/randomUUID) nil {})
                         {} ;; tags
                         [] ;; log-events
                         (now-micros) ;; start-time-micros
                         nil ;; end-time-micros
                         (GlobalTracer/get)  ;; tracer
                         ))
  ([operation context tags log-events start-time-micros end-time-micros tracer]
   (->SplashSpan operation context tags log-events start-time-micros end-time-micros tracer)))

(defn child-context [ctx]
  (->SplashSpanContext (:trace-id ctx) (UUID/randomUUID) (:span-id ctx) (:baggage ctx)))

(deftype SplashSpanBuilder [op-name start-time-micros references tags ignore-active-span scope-manager tracer]
  Tracer$SpanBuilder
  (^Tracer$SpanBuilder asChildOf [this ^Span parent]
   (.addReference this References/CHILD_OF (.context parent)))
  (^Tracer$SpanBuilder asChildOf [this ^SpanContext parent]
   (.addReference this References/CHILD_OF parent))
  (^Tracer$SpanBuilder addReference [this ^String reference-type ^SpanContext parent]
   (set! (.-references this) (conj references [reference-type parent]))
   this)

  (^Tracer$SpanBuilder ignoreActiveSpan [this]
   (set! (.-ignore-active-span this) true)
   this)

  (^Tracer$SpanBuilder withTag [this ^String k ^String v]
   (set! (.-tags this) (assoc tags k v))
   this)
  (^Tracer$SpanBuilder withTag [this ^String k ^boolean v]
   (set! (.-tags this) (assoc tags k v))
   this)
  (^Tracer$SpanBuilder withTag [this ^String k ^Number v]
   (set! (.-tags this) (assoc tags k v))
   this)
  (^Tracer$SpanBuilder withTag [this ^Tag k ^Object v]
   (set! (.-tags this) (assoc tags k v))
   this)

  (^Tracer$SpanBuilder withStartTimestamp [this ^long microseconds]
   (set! (.-start-time-micros this) microseconds)
   this)

  (^Span start [this]
   (let [implicit-parent (and (.isEmpty references) (not ignore-active-span)
                              (not= nil (.activeSpan scope-manager)))

         new-context (cond
                          (and (.isEmpty references) (not ignore-active-span)
                               (not= nil (.activeSpan scope-manager)))
                          (child-context (.-context (.activeSpan scope-manager)))

                          (or (.isEmpty references)
                              (not
                               (and (-> references first second :trace-id)
                                    (-> references first second :span-id))))
                          (->SplashSpanContext (UUID/randomUUID) (UUID/randomUUID) nil {})

                          :else
                          (child-context (-> references first second)))

         start-timestamp (or start-time-micros (now-micros))]
     (get-span op-name new-context tags [] start-timestamp nil tracer))))

(defn parse-tracestate [tracestate]
  (if (and tracestate (seq tracestate))
    (into {} (map (fn [item]
                    (let [[k v] (str/split item #"=")]
                      [(-> k str/lower-case keyword) v]))
                  (str/split tracestate #",")))
    {}))


(defrecord SplashTracer [scope-manager service-name opentracing-client]
  Tracer
  (^ScopeManager scopeManager [this] scope-manager)
  (^Span activeSpan [this] (.activeSpan scope-manager))
  (^Scope activateSpan [this ^Span span] (.activate scope-manager span))

  (buildSpan [this op-name]
   (->SplashSpanBuilder op-name nil [] {} false scope-manager this))

  (inject [this sc format carrier]
    (.put carrier "traceparent" (str "00-" (.toTraceId sc) "-" (.toSpanId sc) "-01"))
    (let [baggage-seq (.baggageItems sc)]
      (when (seq baggage-seq)
        (.put carrier "tracestate" (str/join "," (map #(str (.getKey %) "=" (.getValue %))))))))
  (extract [this fmt carrier]
    (let [entries (into {} (map (juxt #(-> (.getKey %) str/lower-case keyword) #(.getValue %))
                                (iterator-seq (.iterator carrier))))
          {:keys [traceparent tracestate]} entries]
      (when (seq traceparent)
        (try
          (let [[_ traceid parent-span-id _] (str/split traceparent #"-")
                baggage (parse-tracestate tracestate)
                span-id (UUID/randomUUID)]
            (prn "extracted span" (decode-uuid traceid) span-id (decode-uuid parent-span-id))
            (->SplashSpanContext (decode-uuid traceid) (decode-uuid parent-span-id) nil (or baggage {})))
          (catch Exception e
            (do
              (log-info  (str "invalid trace data: " (pr-str entries)))
              (throw (IllegalArgumentException. (str "invalid trace data: " (pr-str entries))))))))))

  (^void close [this] nil))

(defn get-tracer [service-name client-config]
  (SplashTracer. (ThreadLocalScopeManager.)
                service-name
                {}))
