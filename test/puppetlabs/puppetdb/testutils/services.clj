(ns puppetlabs.puppetdb.testutils.services
  (:refer-clojure :exclude [get])
  (:require [puppetlabs.puppetdb.testutils :as testutils]
            [puppetlabs.puppetdb.testutils.db
             :refer [*db* *read-db* with-unconnected-test-db]]
            [puppetlabs.puppetdb.testutils.log :refer [notable-pdb-event?]]
            [puppetlabs.puppetdb.time :as time :refer [now parse-period]]
            [puppetlabs.trapperkeeper.testutils.logging :refer [with-log-suppressed-unless-notable]]
            [metrics.counters :refer [clear!]]
            [clojure.walk :as walk]
            [puppetlabs.trapperkeeper.app :as tk-app :refer [get-service]]
            [puppetlabs.trapperkeeper.testutils.bootstrap :as tkbs]
            [puppetlabs.trapperkeeper.services.webserver.jetty10-service :refer [jetty10-service]]
            [puppetlabs.trapperkeeper.services.webrouting.webrouting-service :refer [webrouting-service]]
            [puppetlabs.trapperkeeper.services.status.status-service :refer [status-service]]
            [puppetlabs.trapperkeeper.services.scheduler.scheduler-service :refer [scheduler-service]]
            [puppetlabs.trapperkeeper.services.metrics.metrics-service :refer [metrics-webservice]]
            [puppetlabs.puppetdb.client :refer [submit-command-via-http!]]
            [puppetlabs.puppetdb.cli.services :refer [puppetdb-service]]
            [puppetlabs.puppetdb.command :refer [command-service] :as dispatch]
            [puppetlabs.puppetdb.http :refer [json-utf8-ctype?]]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [puppetlabs.puppetdb.config :as conf :refer [config-service]]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.utils :refer [base-url->str base-url->str-with-prefix]]
            [clojure.tools.logging :as log]
            [puppetlabs.puppetdb.dashboard :refer [dashboard-redirect-service]]
            [puppetlabs.puppetdb.pdb-routing :refer [pdb-routing-service
                                                     maint-mode-service]]
            [puppetlabs.http.client.sync :as http]
            [puppetlabs.puppetdb.schema :as pls]
            [ring.util.response :as rr]))

;; See utils.clj for more information about base-urls.
(def ^:dynamic *base-url* nil) ; Will not have a :version.

(defn create-temp-config
  "Returns a config that refers to a temporary vardir."
  []
  {:nrepl {}
   :global {:vardir (testutils/temp-dir)}
   :puppetdb {:query-timeout-default "0"
              :query-timeout-max "0"}
   :node-purge-ttl "14d"
   :jetty {:port 0}
   :command-processing {}})

(defn create-default-globals
  ([db] (create-default-globals db db))
  ([read-db write-db]
   {:product-name "puppetdb"
    :url-prefix ""
    :scf-read-db read-db
    :scf-write-dbs [write-db]
    :scf-write-db-names ["default"]
    :node-purge-ttl (parse-period "14d")
    :query-timeout-default ##Inf
    :query-timeout-max ##Inf
    :add-agent-report-filter true
    :log-queries false
    :update-server "FOO"}))

(defn open-port-num
  "Returns a currently open port number"
  []
  (with-open [s (java.net.ServerSocket. 0)]
    (.getLocalPort s)))

(defn assoc-open-port
  "Updates `config` to include a (currently open) port number"
  [config]
  (let [port-key (if (-> config :jetty :ssl-port)
                   :ssl-port
                   :port)]
    (assoc-in config [:jetty port-key] (open-port-num))))

(def ^:dynamic *server* nil)

(def default-services
  [#'jetty10-service
   #'webrouting-service
   #'puppetdb-service
   #'command-service
   #'status-service
   #'scheduler-service
   #'metrics-webservice
   #'dashboard-redirect-service
   #'pdb-routing-service
   #'maint-mode-service
   #'config-service])

(defn clear-counters!
  [metrics]
  (walk/postwalk
    (fn [x] (if (= (type x)
                   com.codahale.metrics.Counter)
              (clear! x)
              x))
    metrics))

(defn run-test-puppetdb [config services bind-attempts]
  (when (zero? bind-attempts)
    (throw (RuntimeException. "Repeated attempts to bind port failed, giving up")))
  (let [config (-> config
                   conf/adjust-and-validate-tk-config
                   assoc-open-port)
        port (or (get-in config [:jetty :port])
                 (get-in config [:jetty :ssl-port]))
        is-ssl (boolean (get-in config [:jetty :ssl-port]))
        base-url {:protocol (if is-ssl "https" "http")
                  :host "localhost"
                  :port port
                  :prefix "/pdb/query"
                  :version :v4}]
    (try
      (swap! dispatch/metrics clear-counters!)
      {:app (tkbs/bootstrap-services-with-config (map var-get services) config)
       :base-url base-url}

      (catch java.net.BindException e
        (log/errorf e "Error occured when Jetty tried to bind to port %s, attempt #%s"
                    port bind-attempts)
        (run-test-puppetdb config services (dec bind-attempts))))))

(defn call-with-puppetdb
  "Calls (f) after starting a puppetdb instance as specified by the
  opts, and tears the instance down when the call exits.

  For the duration of (f), binds *server* to the instance and
  *base-url* to the instance's URL.  Starts any specified services in
  addition to the core PuppetDB services, and tries to bind to an HTTP
  port up to bind-attempts times (default 10) before giving up.  Makes
  the (config :database) the active jdbc connection via
  with-db-connection.  If a config is not specified, one will be
  generated by create-temp-config, using a database managed by
  with-unconnected-test-db.  When to-config is specified (to-config
  pending-config) is called just before starting the service to
  produce the final config, e.g. :to-config #(assoc-in
  % [:puppetdb ::conf/test ...] ...)."
  [{:keys [config services bind-attempts to-config]
    :or {bind-attempts 10
         services default-services
         to-config identity}
    :as opts}
   f]
  (assert (empty? (dissoc opts :config :services :bind-attempts :to-config)))
  (if-not (seq config)
    (with-unconnected-test-db
      (call-with-puppetdb (assoc opts :config
                                 (assoc (create-temp-config)
                                        :database *db*
                                        :read-database *read-db*))
                          f))
    (let [{:keys [app base-url]}
          (run-test-puppetdb (to-config config) services bind-attempts)]
      (try
        (binding [*server* app
                  *base-url* base-url]
          (jdbc/with-db-connection (:database config)
            (f)))
        (finally
          (tk-app/stop app))))))

(defmacro with-puppetdb
  [config & body]
  `(call-with-puppetdb ~config (fn [] ~@body)))

(defn ^{:deprecated "8.0.2"}
  call-with-puppetdb-instance
  "Calls f after starting a puppetdb instance.  Deprecated wrapper for
  call-with-puppetdb."
  ([f] (call-with-puppetdb nil f))
  ([config f] (call-with-puppetdb {:config config} f))
  ([config services f] (call-with-puppetdb {:config config :services services} f))
  ([config services bind-attempts f]
   (call-with-puppetdb {:config config :services services :bind-attempts bind-attempts} f)))

(defmacro ^{:deprecated "8.0.2"} with-puppetdb-instance
  "Convenience macro to launch a puppetdb instance via
  call-with-puppetdb.  Prefer with-puppetdb."
  [& body]
  `(call-with-puppetdb-instance
    (fn []
      ~@body)))

(defn create-url-str [base-url url-suffix]
  (str (base-url->str base-url)
       (when url-suffix
         url-suffix)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; Functions that return URLs and strings for the top level services
;; in PDB
;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn pdb-query-url
  ([]
   (pdb-query-url *base-url*))
  ([base-url]
   (assoc base-url :prefix "/pdb/query" :version :v4)))

(defn query-url-str
  ([url-suffix]
   (query-url-str *base-url* url-suffix))
  ([base-url url-suffix]
   (create-url-str (pdb-query-url base-url) url-suffix)))

(defn pdb-cmd-url
  ([]
   (pdb-cmd-url *base-url*))
  ([base-url]
   (assoc base-url :prefix "/pdb/cmd" :version :v1)))

(defn cmd-url-str
  ([url-suffix]
   (cmd-url-str *base-url* url-suffix))
  ([base-url url-suffix]
   (create-url-str (pdb-cmd-url base-url) url-suffix)))

(defn pdb-admin-url
  ([]
   (pdb-admin-url *base-url*))
  ([base-url]
   (assoc base-url :prefix "/pdb/admin" :version :v1)))

(defn admin-url-str
  ([url-suffix]
   (admin-url-str *base-url* url-suffix))
  ([base-url url-suffix]
   (create-url-str (pdb-admin-url base-url) url-suffix)))

(defn pdb-metrics-url
  ([]
   (pdb-metrics-url *base-url*))
  ([base-url]
   (assoc base-url :prefix "/metrics" :version :v1)))

(defn metrics-url-str
  ([url-suffix]
   (metrics-url-str *base-url* url-suffix))
  ([base-url url-suffix]
   (create-url-str (pdb-metrics-url base-url) url-suffix)))

(defn pdb-meta-url
  ([]
   (pdb-meta-url *base-url*))
  ([base-url]
   (assoc base-url :prefix "/pdb/meta" :version :v1)))

(defn meta-url-str
  ([url-suffix]
   (meta-url-str *base-url* url-suffix))
  ([base-url url-suffix]
   (create-url-str (pdb-meta-url base-url) url-suffix)))

(defn root-url-str
  ([]
   (root-url-str *base-url*))
  ([base-url]
   (-> base-url
       (assoc :prefix "/")
       base-url->str-with-prefix)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; Basic HTTP functions for interacting with PuppetDB services, the
;; url-str arguments below will likely come from the above functions
;; relating to the various top level services
;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(pls/defn-validated get-unparsed
  "Executes a GET HTTP request against `url-str`. `opts` are merged
  into the request map. The response is unparsed but returned as a
  string."
  [url-str :- String
   & [opts]]
  (http/get url-str
            (merge
             {:as :text
              :headers {"Content-Type" "application/json"}}
             opts)))

(pls/defn-validated get
  "Executes a GET HTTP request against `url-str`. `opts` are merged
  into the request map. JSON responses are automatically parsed before
  returning. Error responses are returned (i.e. 404) and not thrown."
  [url-str :- String
   & [opts]]
  (let [resp (get-unparsed url-str opts)
        ctype (rr/get-header resp "content-type")
        ;; See https://www.ietf.org/rfc/rfc4627.txt, UTF-8 is assumed as a default
        ;; encoding.
        json-ctype? #(= % "application/json")]
    (if (and ctype (string? (:body resp)) (or (json-ctype? ctype) (json-utf8-ctype? ctype)))
      (update resp :body #(json/parse-string % true))
      resp)))

(def default-ca-cert "test-resources/puppetserver/ssl/certs/ca.pem")
(def default-cert "test-resources/puppetserver/ssl/certs/localhost.pem")
(def default-ssl-key "test-resources/puppetserver/ssl/private_keys/localhost.pem")

(pls/defn-validated get-ssl
  "Executes a mutually authenticated GET HTTPS request against
  `url-str` using the above `get` function."
  [url-str & [opts]]
  (get url-str
       (merge {:ssl-ca-cert default-ca-cert
               :ssl-cert default-cert
               :ssl-key  default-ssl-key}
              opts)))

(pls/defn-validated get-or-throw
  "Same as `get` except will throw if an error status is returned."
  [url-str :- String
   & [opts]]
  (let [resp (get url-str opts)]
    (if (>= (:status resp) 400)
      (throw
       (ex-info (format "Failed request to '%s' with status '%s'"
                        url-str (:status resp))
                {:url url-str
                 :response resp
                 :status (:status resp)}))
      resp)))

(pls/defn-validated post
  "Executes a POST HTTP request against `url-str`. `body` is a clojure
  data structure that is converted to a JSON string before POSTing."
  [url-str :- String
   body
   & [opts]]
  (http/post url-str
             (merge
              {:body (json/generate-string body)
               :headers {"Content-Type" "application/json"}}
              opts)))

(pls/defn-validated post-ssl
  "Executes a mutually authenticated POST HTTP request against
  `url-str`. Uses the above `post` function"
  ([url-str body] (post-ssl url-str body nil))
  ([url-str :- String
    body
    opts]
   (post url-str
         body
         (merge {:ssl-ca-cert default-ca-cert
                 :ssl-cert default-cert
                 :ssl-key  default-ssl-key}
                opts))))

(pls/defn-validated post-ssl-or-throw
  "Same as `post-ssl` except will throw if an error status is returned."
  [url-str :- String
   body]
  (let [resp (post-ssl url-str body)]
    (if (>= (:status resp) 400)
      (throw
       (ex-info (format "Failed request to '%s' with status '%s'"
                        url-str (:status resp))
                {:url url-str
                 :response resp
                 :status (:status resp)}))
      resp)))

(defn certname-query
  "Returns a function that will query the given endpoint (`suffix`)
  for the provided `certname`"
  [base-url suffix certname]
  (-> (query-url-str base-url suffix)
      (get-or-throw {:query-params {"query" (json/generate-string [:= :certname certname])}})
      :body))

(defn filter-query
  "Query the given endpoint using the provided filter"
  [base-url suffix filter]
  (-> (query-url-str base-url suffix)
      (get-or-throw {:query-params {"query" (json/generate-string filter)}})
      :body))

(defn filter-reports
  ([filter]
   (filter-reports filter *base-url*))
  ([filter base-url]
   (filter-query base-url "/reports" filter)))

(defn get-reports
  ([certname]
   (get-reports *base-url* certname))
  ([base-url certname]
   (certname-query base-url "/reports" certname)))

(defn get-events
  ([certname]
   (get-events *base-url* certname))
  ([base-url certname]
   (certname-query base-url "/events" certname)))

(defn get-factsets
  ([certname]
   (get-factsets *base-url* certname))
  ([base-url certname]
   (certname-query base-url "/factsets" certname)))

(defn get-catalogs
  ([certname]
   (get-catalogs *base-url* certname))
  ([base-url certname]
   (certname-query base-url "/catalogs" certname)))

(defn get-all-catalog-inputs
  ([base-url]
   (-> (query-url-str base-url "/catalog-inputs")
       get-or-throw
       :body)))

(defn get-summary-stats []
  (-> (admin-url-str "/summary-stats")
      get-or-throw
      :body))

(def ^:dynamic *notable-log-event?* notable-pdb-event?)

(defn call-with-single-quiet-pdb-instance
  "Calls the call-with-puppetdb-instance with args after suppressing
  all log events.  If there's an error or worse, prints the log to
  *err*.  Should not be nested, nor nested with calls to
  with-log-suppressed-unless-notable."
  [& args]
  (with-log-suppressed-unless-notable *notable-log-event?*
    (apply call-with-puppetdb-instance args)))

(defmacro with-single-quiet-pdb-instance
  "Convenience macro for call-with-single-quiet-pdb-instance."
  [& body]
  `(call-with-single-quiet-pdb-instance
    (fn [] ~@body)))

(defmacro with-pdb-with-no-gc [& body]
  `(with-unconnected-test-db
     (call-with-single-quiet-pdb-instance
      (-> (svc-utils/create-temp-config)
          (assoc :database *db*
                 :read-database *read-db*)
          (assoc-in [:database :gc-interval] "0"))
      (fn []
        ~@body))))

(def max-attempts 50)

(defn url-encode [s]
  (java.net.URLEncoder/encode s "UTF-8"))

(defn discard-count
  "Returns the number of messages discarded from the command queue by
  the current PuppetDB instance."
  []
  (-> (pdb-metrics-url "/mbeans/puppetlabs.puppetdb.command:type=global,name=discarded")
      get-or-throw
      (get-in [:body :Count])))

(defn sync-command-post
  "Syncronously post a command to PDB by blocking until the message is consumed
   off the queue."
  [base-url certname cmd version payload]
  (let [response (submit-command-via-http! base-url certname cmd version payload
                                           {:timeout 20})]
    (if (>= (:status response) 400)
      (throw (ex-info "Command processing failed" {:response response}))
      response)))

(defn wait-for-server-processing
  "Returns a truthy value indicating whether the wait was
  successful (i.e. didn't time out).  The current timeout granularity
  may be as bad as 10ms."
  [server timeout-ms]
  (let [now-ms #(time/to-long (now))
        deadline (+ (now-ms) timeout-ms)]
    (loop []
      (cond
        (> (now-ms) deadline)
        false

        (let [srv (get-service server :PuppetDBCommandDispatcher)
              stats (dispatch/stats srv)]
          (= (:executed-commands stats) (:received-commands stats)))
        true

        :else ;; In theory, polling might be avoided via watcher.
        (do
          (Thread/sleep 10)
          (recur))))))
