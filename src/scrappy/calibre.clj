(ns scrappy.calibre
  (:require [clj-http.client :as http]
            [clj-http.util :as chu]
            [clojure.java.io :as io]
            [clojure.string :as cs]
            [clojure.tools.logging :as ctl]
            [com.climate.claypoole :as cp])
  (:import io.aleph.dirigiste.Executor
           java.io.File
           java.lang.Character
           [java.util.concurrent Executors LinkedBlockingQueue TimeUnit]
           java.util.EnumSet
           org.jsoup.Jsoup))

(defn- url->filename
  [file-url]
  (-> file-url
      (cs/split #"/")
      last
      chu/url-decode))

(defn- download-file
  [dirname fileurl]
  (let [filename (str dirname (url->filename fileurl))]
    (if (.exists (io/as-file filename))
      (ctl/info (format "File: %s already exists, not doing anything here."
                        filename))
      (do (ctl/info (format "Downloading File: %s" fileurl))
          (io/make-parents filename)
          (io/copy (:body (http/get fileurl {:as :stream}))
                   (File. filename))))))

(defn- process-title-and-author
  [taa-str]
  (when-let [[_ title author] (re-find #"(.+) by (.+)" taa-str)]
    [title author]))

(defn- ^String trim-weird-space-and-whitespace
  "Removes weird space chars used by Calibre, along with standard trimming"
  [^CharSequence s]
  (let [len (.length s)]
    (loop [rindex len]
      (if (zero? rindex)
        ""
        (if (or (Character/isSpaceChar (.charAt s (dec rindex)))
                (Character/isWhitespace (.charAt s (dec rindex))))
          (recur (dec rindex))
          ;; there is at least one non-whitespace char in the string,
          ;; so no need to check for lindex reaching len.
          (loop [lindex 0]
            (if (or (Character/isSpaceChar (.charAt s lindex))
                    (Character/isWhitespace (.charAt s lindex)))
              (recur (inc lindex))
              (.. s (subSequence lindex rindex) toString))))))))

(defn- process-book-entry
  [req be]
  (let [[title author] (-> be
                           (.select "span.first-line")
                           first
                           .text
                           process-title-and-author)
        uri (str (name (:scheme req))
                 "://"
                 (:server-name req)
                 ":"
                 (:server-port req))]
    {:links (map (fn [l] (str uri (.attr l "href")))
                 (.select be "span.button > a[href]"))
     :title (when title (trim-weird-space-and-whitespace title))
     :author (when author (trim-weird-space-and-whitespace author))}))

(defn- page->entries
  "Given a page-url, scrape the page, return a list of book entries
  and the next page-url (if any)."
  [page-url]
  (ctl/info "Start Parse: " page-url)
  (let [req (http/parse-url page-url)
        rendered-page (.get (Jsoup/connect page-url))
        book-entries (.select rendered-page "table#listing > tbody > tr")
        next-page-elem (->> (.select rendered-page
                                     "div.navigation > table.buttons > tbody > tr > td.button > a[href]")
                            (filter (fn [a] (= "Next" (.text a))))
                            first)
        next-page-url (when next-page-elem
                        (str (name (:scheme req))
                             "://"
                             (:server-name req)
                             ":"
                             (:server-port req)
                             (.attr next-page-elem "href")))]
    (ctl/info "Done Parse: " page-url)
    [next-page-url (map process-book-entry (repeat req) book-entries)]))

(defn- add-bookdir-to-entry
  "Given a basedir and a book-entry, add the directory where the book
  should be downloaded to the book-entry."
  [basedir be]
  (assoc be
         :bookdir (str basedir (:author be) "/" (:title be) "/")))

(defn- extract-download-links
  "Given a book-entry, return an array of maps with the download link
  and the directory where it should be downloaded."
  [be]
  (map (fn [l]
         {:link l
          :dir (:bookdir be)})
       (:links be)))

(def num-threads (+ 2 (cp/ncpus)))
(def io-pool
  "A pool of threads for network and disk IO."
  (io.aleph.dirigiste.Executor. (Executors/defaultThreadFactory)
                                (LinkedBlockingQueue.)
                                (io.aleph.dirigiste.Executors/fixedController num-threads)
                                num-threads
                                (EnumSet/allOf io.aleph.dirigiste.Stats$Metric)
                                25
                                10000
                                TimeUnit/MILLISECONDS))
(def control-pool
  "A pool of threads for controlling the execution of the scrapper."
  (io.aleph.dirigiste.Executor. (Executors/defaultThreadFactory)
                                (LinkedBlockingQueue.)
                                (io.aleph.dirigiste.Executors/fixedController num-threads)
                                num-threads
                                (EnumSet/allOf io.aleph.dirigiste.Stats$Metric)
                                25
                                10000
                                TimeUnit/MILLISECONDS))

(defn- construct-calibre-listing-url
  [calibre-host calibre-port start-from num-entries]
  (str "http://"
       calibre-host
       ":"
       calibre-port
       "/calibre/mobile?"
       (http/generate-query-string {"search" ""
                                    "order" "descending"
                                    "sort" "date"
                                    "num" num-entries
                                    "start" start-from})))

(defn- download-books
  "The main function to download a given set of books. Starts
  downloading in a future. The number of future threads is controlled
  by the `control-pool`. Each future spawns threads to download a
  version of a book. The number of io threads is controlled by the
  `io-pool`."
  [basedir book-entries]
  (cp/future control-pool
             (->> book-entries
                  (map (partial add-bookdir-to-entry basedir))
                  (mapcat extract-download-links)
                  (cp/upmap io-pool
                            (fn [{:keys [link dir]}]
                              (download-file dir link))))))

(defn download-calibre-entries
  "This is the function that you are probably looking for.

  Given the host and port of a calibre server, scrape it and download
  the books it is hosting to basedir."
  [calibre-host calibre-port basedir]
  (let [first-page-url (construct-calibre-listing-url calibre-host
                                                      calibre-port
                                                      1
                                                      100)]
    (loop [page-url first-page-url
           ;; Adding a control for basic testing (it will stop the
           ;; program in a bit, while I figure out details of how to
           ;; see dirigiste stats.
           control-counter 1]
      (let [[next-page-url book-entries] (page->entries page-url)]
        (download-books basedir book-entries)
        (when (and next-page-url (< control-counter 4))
          (recur next-page-url (inc control-counter)))))))

(defn shutdown-threadpools
  "Shutdown our control and io pools. Existing tasks will be
  completed, pending tasks will not be picked up."
  []
  (cp/shutdown io-pool)
  (cp/shutdown control-pool))

(defn threadpool-stats
  [pool]
  {:queue-length (.getQueueLength (.getStats pool) 0.9)
   :task-latency (.getTaskLatency (.getStats pool) 0.9)})
