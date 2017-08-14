(ns scrappy.calibre
  (:require [clj-http.client :as http]
            [clj-http.util :as chu]
            [clojure.java.io :as io]
            [clojure.string :as cs]
            [clojure.tools.logging :as ctl])
  (:import java.io.File
           java.lang.Character
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
         :bookdir (str basedir (:title be) "/")))
