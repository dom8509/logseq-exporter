(ns logseq-to-markdown.parser
  (:require [clojure.string :as s]
            [logseq-to-markdown.config :as config]
            [logseq-to-markdown.fs :as fs]
            [logseq-to-markdown.utils :as utils]
            [logseq-to-markdown.graph :as graph]))

(defn parse-property-value-list
  [property-value]
  (let [string-value? (string? property-value)
        object-value? (try
                        (and (not string-value?) (> (count property-value) 0))
                        (catch :default _ false))
        iteratable-value? (and object-value? (> (count property-value) 1))
        value-lines (or
                     (and
                      iteratable-value?
                      (let [property-lines (map #(str "- " %) property-value)
                            property-data (s/join "\n" property-lines)]
                        (str "\n" property-data)))
                     (or
                      (and
                       string-value?
                       (str "\n- " property-value))
                      (and
                       object-value?
                       (str "\n- " (first property-value)))
                      (str property-value)))]
    (str value-lines)))

(defn parse-meta-data
  [page]
  (let [original-name (get page :block/original-name)
        trim-namespaces? (config/entry :trim-namespaces)
        namespace? (s/includes? original-name "/")
        namespace (let [tokens (s/split original-name "/")]
                    (s/join "/" (subvec tokens 0 (- (count tokens) 1))))
        title (or (and trim-namespaces? namespace? (last (s/split original-name "/"))) original-name)
        file (str (fs/->filename (or (and namespace? (last (s/split original-name "/"))) original-name)) ".md")
        excluded-properties (config/entry :excluded-properties)
        properties (into {} (filter #(not (contains? excluded-properties (first %))) (get page :block/properties)))
        tags (get properties :tags)
        categories (get properties :categories)
        created-at (utils/->hugo-date (get page :block/created-at))
        updated-at (utils/->hugo-date (get page :block/updated-at))
        page-data (s/join ""
                          ["---\n"
                           (str "title: " title "\n")
                           (when namespace? (str "namespace: " namespace "\n"))
                           (str "tags: " (parse-property-value-list tags) "\n")
                           (str "categories: " (parse-property-value-list categories) "\n")
                           (str "date: " created-at "\n")
                           (str "lastMod: " updated-at "\n")
                           "---\n"])]
    (when (config/entry :verbose)
      (println "======================================")
      (println (str "Title: " title))
      (println (str "Namespace?: " namespace?))
      (println (str "Namespace: " namespace))
      (println (str "File: " file))
      (println (str "Excluded Properties: " excluded-properties))
      (println (str "Properties: " properties))
      (println (str "Tags: " tags))
      (println (str "Categories: " categories))
      (println (str "Created at: " created-at))
      (println (str "Updated at: " updated-at)))
    {:filename file
     :namespace namespace
     :data page-data}))

(defn parse-block-refs
  [text]
  (let [pattern #"\(\(([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\)\)"
        block-ref-text (re-find pattern text)
        alias-pattern #"\[([^\[]*?)\]\([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\)"
        alias-text (re-find alias-pattern text)]
    (if (empty? block-ref-text)
      (if (empty? alias-text)
        (str text)
        (str (last alias-text)))
      (let [block-ref-id (last block-ref-text)
            data (graph/get-ref-block block-ref-id)]
        (if (seq data)
          (let [id-pattern (re-pattern (str "id:: " block-ref-id))]
            (s/replace data id-pattern ""))
          (str text))))))

(defn parse-image
  [text]
  (let [pattern #"!\[.*?\]\((.*?)\)"
        image-text (re-find pattern text)]
    (if (empty? image-text)
      (str text)
      (let [link (nth image-text 1)
            converted-link (s/replace link #"\.\.\/" "/")
            converted-text (s/replace text #"\.\.\/" "/")]
        (if (not (or (s/includes? link "http") (s/includes? link "pdf")))
          (do
            (fs/copy-file
             (str (graph/get-logseq-data-path) converted-link)
             (str (config/entry :outputdir) converted-link))
            (str converted-text))
          (str text))))))

(def diagram-code (atom {:header-found false :type ""}))
(def diagram-code-count (atom 0))

(defn parse-diagram-as-code
  [text]
  (let [header-pattern #"{{renderer code_diagram,(.*?)}}"
        header-res (re-find header-pattern text)
        body-pattern #"(?s)```([a-z]*)\n(.*)```"
        body-res (re-find body-pattern text)]
    (if (empty? header-res)
      (if (empty? body-res)
        (do
          (reset! diagram-code {:header-found false :type ""})
          (str text))
        (let [res-str (str
                       "{{<diagram name=\"code_diagram_" @diagram-code-count "\" type=\"" (:type @diagram-code) "\">}}\n"
                       (last body-res)
                       "{{</diagram>}}")]
          (swap! diagram-code-count inc 1)
          res-str))
      (do
        (reset! diagram-code {:header-found true :type (last header-res)})
        (str "")))))

(defn parse-excalidraw-diagram
  [text]
  (let [pattern #"\[\[draws/(.*?)\]\]"
        res (re-find pattern text)]
    (if (empty? res)
      (str text)
      (let [diagram-name (first (s/split (last res) "."))
            diagram-file (str (graph/get-logseq-data-path) "/draws/" (last res))
            diagram-content (fs/slurp diagram-file)]
        (str "{{<diagram name=\"" diagram-name "\" type=\"excalidraw\">}}\n"
             diagram-content "\n"
             "{{</diagram>}}")))))

(defn parse-links
  [text]
  (let [link-pattern #"\[\[(.*?)\]\]"
        link-res (re-seq link-pattern text)
        desc-link-pattern #"\[(.*?)\]\(\[\[(.*?)\]\]\)"
        desc-link-res (re-seq desc-link-pattern text)]
    (if (empty? desc-link-res)
      (if (empty? link-res)
        (str text)
        (reduce
         #(let [current-text (first %2)
                current-link (last %2)
                namespace-pattern #"\[\[([^\/]*\/).*\]\]"
                namespace-res (re-find namespace-pattern text)
                namespace-link? (not-empty namespace-res)
                link-text (or (and namespace-link? (config/entry :trim-namespaces)
                                   (last (s/split current-link "/"))) current-link)
                replaced-str (or (and (graph/page-exists? current-link) (str "[[[" link-text "]]]({{< ref \"/pages/" (fs/->filename current-link) "\" >}})"))
                                 (str link-text))]
            (s/replace %1 current-text replaced-str))
         text
         link-res))
      (reduce #(let [current-text (first %2)
                     current-link (last %2)
                     link-text (nth %2 1)
                     replaced-str (or (and (graph/page-exists? current-link) (str "[" link-text "]({{< ref \"/pages/" (fs/->filename current-link) "\" >}})"))
                                      (str link-text))]
                 (s/replace %1 current-text replaced-str))
              text
              desc-link-res))))

(defn parse-namespaces
  [level text]
  (let [pattern #"{{namespace\s([^}]+)}}"
        res (re-find pattern text)]
    (if (empty? res)
      (str text)
      (let [namespace-name (last res)
            data (graph/get-namespace-pages namespace-name)]
        (if (seq data)
          (let [heading (str "***Namespace "
                             (if (graph/page-exists? namespace-name)
                               (str "[" namespace-name "]({{< ref \"/pages/" (fs/->filename namespace-name) "\" >}})***\n")
                               (str namespace-name "***\n")))
                prefix (if (config/entry :keep-bullets)
                         (str (apply str (concat (repeat (* level 1) "\t"))) "+ ")
                         (str (apply str (concat (repeat (* (- level 1) 1) "\t"))) "+ "))
                content (reduce #(str %1 prefix "[" %2 "]({{< ref \"/pages/" (fs/->filename %2) "\" >}})\n") "" data)]
            (str heading content))
          (str text))))))

;; TODO parse-embeds
(defn parse-embeds
  [text]
  (str text))

(defn parse-video
  [text]
  (let [pattern #"{{(?:video|youtube) (.*?)}}"
        res (re-find pattern text)]
    (if (empty? res)
      (str text)
      (let [title-pattern #"(youtu(?:.*\/v\/|.*v\=|\.be\/))([A-Za-z0-9_\-]{11})"
            title (re-find title-pattern text)]
        (if (empty? title)
          (str text)
          (str "{{< youtube " (last title) " >}}"))))))

;; TODO parse-markers
(defn parse-markers
  [text]
  (str text))

(defn parse-highlights
  [text]
  (let [pattern #"(==(.*?)==)"]
    (s/replace text pattern "{{< logseq/mark >}}$2{{< / logseq/mark >}}")))

(defn parse-org-cmd
  [text]
  (let [pattern #"(?sm)#\+BEGIN_([A-Z]*)[^\n]*\n(.*)#\+END_[^\n]*"
        res (re-find pattern text)]
    (if (empty? res)
      (str text)
      (let [cmd (nth res 1)
            value (nth res 2)]
        (str "{{< logseq/org" cmd " >}}" value "{{< / logseq/org" cmd " >}}\n")))))

(defn rm-logbook-data
  [text]
  (let [pattern #"(?s)(:LOGBOOK:.*:END:)"
        res (re-find pattern text)]
    (if (empty? res)
      (str text)
      (str ""))))

(defn rm-page-properties
  [text]
  (let [pattern #"([A-Za-z0-9_\-]+::.*)"
        res (re-find pattern text)]
    (if (empty? res)
      (str text)
      (str (rm-page-properties (s/replace text (first res) ""))))))

(defn rm-width-height
  [text]
  (let [pattern #"{:height\s*[0-9]*,\s*:width\s*[0-9]*}"]
    (s/replace text pattern "")))

(defn rm-brackets
  [text]
  (let [pattern #"(?:\[\[|\]\])"
        res (re-find pattern text)]
    (if (or (empty? res) (false? (config/entry :rm-brackets)))
      (str text)
      (str (rm-brackets (s/replace text pattern ""))))))

;; Parse the text of the :block/content and convert it into markdown
(defn parse-text
  [block]
  (let [current-block-data (get block :data)
        block-level (get block :level)]
    (when (not (and (get current-block-data :block/pre-block?) (= block-level 1)))
      (let [prefix (if (and (config/entry :keep-bullets)
                            (not-empty (get current-block-data :block/content)))
                     (str (apply str (concat (repeat (* (- block-level 1) 1) "\t"))) "+ ")
                     (if (and (not= block-level 1)
                              (not-empty (get current-block-data :block/content)))
                       (str (apply str (concat (repeat (* (- block-level 2) 1) "\t"))) "+ ")
                       (str "")))
            block-content (get current-block-data :block/content)
            marker? (not (nil? (get current-block-data :block/marker)))]
        (when (or (not marker?) (true? (config/entry :export-tasks)))
          (let [res-line (s/trim-newline (->> (str block-content)
                                              (parse-block-refs)
                                              (parse-image)
                                              (parse-diagram-as-code)
                                              (parse-excalidraw-diagram)
                                              (parse-links)
                                              (parse-namespaces block-level)
                                              (parse-embeds)
                                              (parse-video)
                                              (parse-markers)
                                              (parse-highlights)
                                              (parse-org-cmd)
                                              (rm-logbook-data)
                                              (rm-page-properties)
                                              (rm-width-height)
                                              (rm-brackets)
                                              (str prefix)))]
            (when (not= res-line "")
              (str res-line "\n\n"))))))))

;; Iterate over every block and parse the :block/content
(defn parse-block-content
  [block-tree]
  (when (not-empty block-tree)
    (let [current-block (first block-tree)]
      (str (parse-text current-block)
           (parse-block-content (get current-block :children))
           (parse-block-content (rest block-tree))))))

(defn parse-page-blocks
  [graph-db page]
  (let [meta-data (parse-meta-data page)
        first-block-id (get page :db/id)
        block-tree (graph/get-block-tree graph-db first-block-id first-block-id 1)
        content-data (parse-block-content block-tree)
        page-data (str
                   (get meta-data :data)
                   content-data)]
    {:filename (get meta-data :filename)
     :namespace (get meta-data :namespace)
     :data page-data}))