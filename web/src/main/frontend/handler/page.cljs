(ns frontend.handler.page
  (:require [clojure.string :as string]
            [frontend.db :as db]
            [datascript.core :as d]
            [frontend.state :as state]
            [frontend.util :as util :refer-macros [profile]]
            [frontend.tools.html-export :as html-export]
            [frontend.config :as config]
            [frontend.handler :as handler]
            [frontend.handler.route :as route-handler]
            [frontend.handler.file :as file-handler]
            [frontend.handler.project :as project-handler]
            [frontend.handler.notification :as notification]
            [frontend.date :as date]
            [clojure.walk :as walk]
            [frontend.git :as git]
            [frontend.fs :as fs]
            [promesa.core :as p]))

(defn page-add-directives!
  [page-name directives]
  (when-let [directives-content (string/trim (db/get-page-directives-content page-name))]
    (let [page (db/entity [:page/name page-name])
          file (db/entity (:db/id (:page/file page)))
          file-path (:file/path file)
          file-content (db/get-file file-path)
          after-content (subs file-content (inc (count directives-content)))
          page-format (db/get-page-format page-name)
          new-directives-content (db/add-directives! page-format directives-content directives)
          full-content (str new-directives-content "\n\n" (string/trim after-content))]
      (file-handler/alter-file (state/get-current-repo)
                          file-path
                          full-content
                          {:reset? true
                           :re-render-root? true}))))

(defn page-remove-directive!
  [page-name k]
  (when-let [directives-content (string/trim (db/get-page-directives-content page-name))]
    (let [page (db/entity [:page/name page-name])
          file (db/entity (:db/id (:page/file page)))
          file-path (:file/path file)
          file-content (db/get-file file-path)
          after-content (subs file-content (count directives-content))
          page-format (db/get-page-format page-name)
          new-directives-content (let [lines (string/split-lines directives-content)
                                       prefix (case page-format
                                                :org (str "#+" (string/upper-case k) ": ")
                                                :markdown (str (string/lower-case k) ": ")
                                                "")
                                       exists? (atom false)
                                       lines (remove #(string/starts-with? % prefix) lines)]
                                   (string/join "\n" lines))
          full-content (str new-directives-content "\n\n" (string/trim after-content))]
      (file-handler/alter-file (state/get-current-repo)
                          file-path
                          full-content
                          {:reset? true
                           :re-render-root? true}))))

(defn published-success-handler
  [page-name]
  (fn [result]
    (let [permalink (:permalink result)]
      (page-add-directives! page-name {"permalink" permalink})
      (let [win (js/window.open (str
                                 config/website
                                 "/"
                                 (state/get-current-project)
                                 "/"
                                 permalink))]
        (.focus win)))))

(defn published-failed-handler
  [error]
  (notification/show!
   "Publish failed, please give it another try."
   :error))

(defn get-plugins
  [headings]
  (let [plugins (atom {})
        add-plugin #(swap! plugins assoc % true)]
    (walk/postwalk
     (fn [x]
       (if (and (vector? x)
                (>= (count x) 2))
         (let [[type option] x]
           (case type
             "Src" (when (:language option)
                     (add-plugin "highlight"))
             "Export" (when (= option "latex")
                        (add-plugin "latex"))
             "Latex_Fragment" (add-plugin "latex")
             "Math" (add-plugin "latex")
             "Latex_Environment" (add-plugin "latex")
             nil)
           x)
         x))
     (map :heading/body headings))
    @plugins))

(defn publish-page-as-slide!
  ([page-name]
   (publish-page-as-slide! page-name (db/get-page-headings page-name)))
  ([page-name headings]
   (project-handler/exists-or-create!
    (fn [project]
      (page-add-directives! page-name {"published" true
                                       "slide" true})
      (let [directives (db/get-page-directives page-name)
            plugins (get-plugins headings)
            data {:project project
                  :title page-name
                  :permalink (:permalink directives)
                  :html (html-export/export-page page-name headings notification/show!)
                  :tags (:tags directives)
                  :settings (merge
                             (assoc directives
                                    :slide true
                                    :published true)
                             plugins)
                  :repo (state/get-current-repo)}]
        (util/post (str config/api "pages")
                   data
                   (published-success-handler page-name)
                   published-failed-handler))))))

(defn publish-page!
  [page-name]
  (project-handler/exists-or-create!
   (fn [project]
     (let [directives (db/get-page-directives page-name)
           slide? (let [slide (:slide directives)]
                    (or (true? slide)
                        (= "true" slide)))
           headings (db/get-page-headings page-name)
           plugins (get-plugins headings)]
       (if slide?
         (publish-page-as-slide! page-name headings)
         (do
           (page-add-directives! page-name {"published" true})
           (let [data {:project project
                       :title page-name
                       :permalink (:permalink directives)
                       :html (html-export/export-page page-name headings notification/show!)
                       :tags (:tags directives)
                       :settings (merge directives plugins)
                       :repo (state/get-current-repo)}]
             (util/post (str config/api "pages")
                        data
                        (published-success-handler page-name)
                        published-failed-handler))))))))

(defn unpublished-success-handler
  [page-name]
  (fn [result]
    (notification/show!
     "Un-publish successfully!"
     :success)))

(defn unpublished-failed-handler
  [error]
  (notification/show!
   "Un-publish failed, please give it another try."
   :error))

(defn unpublish-page!
  [page-name]
  (page-add-directives! page-name {"published" false})
  (let [directives (db/get-page-directives page-name)
        permalink (:permalink directives)
        project (state/get-current-project)]
    (if (and project permalink)
      (util/delete (str config/api project "/" permalink)
                   (unpublished-success-handler page-name)
                   unpublished-failed-handler)
      (notification/show!
       "Can't find the permalink of this page!"
       :error))))

(defn delete!
  [page-name ok-handler]
  (when-not (date/valid-journal-title? page-name)
    (when-let [repo (state/get-current-repo)]
      (let [page-name (string/lower-case page-name)]
        (when-let [file (db/get-page-file page-name)]
          (let [file-path (:file/path file)]
            ;; delete file
            (db/transact! [[:db.fn/retractEntity [:file/path file-path]]])
            (when-let [files-conn (db/get-files-conn repo)]
              (d/transact! files-conn [[:db.fn/retractEntity [:file/path file-path]]]))

            ;; delete headings
            (let [headings (db/get-page-headings page-name)
                  tx-data (mapv
                           (fn [heading]
                             [:db.fn/retractEntity [:heading/uuid (:heading/uuid heading)]])
                           headings)]
              (db/transact! tx-data)
              ;; remove file
              (->
               (p/let [_ (git/remove-file repo file-path)
                       _result (fs/unlink (str (util/get-repo-dir repo)
                                               "/"
                                               file-path)
                                          nil)]
                 (state/git-add! repo (str "- " file-path)))
               (p/catch (fn [err]
                          (prn "error: " err))))

              (db/transact! [[:db.fn/retractEntity [:page/name page-name]]])

              (ok-handler))))))))

(defn rename!
  [old-name new-name]
  (when-let [repo (state/get-current-repo)]
    (when-let [page (db/entity [:page/name (string/lower-case old-name)])]
      (let [old-original-name (:page/original-name page)]
        (d/transact! (db/get-conn repo false)
          [{:db/id (:db/id page)
            :page/name (string/lower-case new-name)
            :page/original-name new-name}])

        (page-add-directives! (string/lower-case new-name) {:title new-name})

        ;; update all files which have references to this page
        (let [files (db/get-files-that-referenced-page (:db/id page))]
          (doseq [file-path files]
            (let [file-content (db/get-file file-path)
                  ;; FIXME: not safe
                  new-content (string/replace file-content
                                              (util/format "[[%s]]" old-original-name)
                                              (util/format "[[%s]]" new-name))]
              (file-handler/alter-file repo
                                  file-path
                                  new-content
                                  {:reset? true
                                   :re-render-root? false})))))

      ;; TODO: update browser history, remove the current one

      ;; Redirect to the new page
      (route-handler/redirect! {:to :page
                          :path-params {:name (util/encode-str (string/lower-case new-name))}})

      (notification/show! "Page renamed successfully!" :success))))