(ns stylefy.impl.dom
  (:require [dommy.core :as dommy]
            [reagent.core :as r]
            [garden.core :refer [css]]
            [cljs.core.async :refer [<! timeout]]
            [stylefy.impl.cache :as cache]
            [stylefy.impl.utils :as utils]
            [stylefy.impl.conversion :as conversion]
            [garden.stylesheet :refer [at-media at-keyframes at-font-face]]
            [clojure.set :as set])
  (:require-macros
    [reagent.ratom :refer [run!]]
    [cljs.core.async.macros :refer [go]]))

(def stylefy-initialised? (r/atom false))
(def styles-in-use (r/atom {})) ;; style hash -> map containing keys: ::css & ::in-dom?
(def keyframes-in-use (r/atom [])) ;; Vector of maps containing keys: ::css
(def font-faces-in-use (r/atom [])) ;; Vector of maps containing keys: ::css
(def custom-tags-in-use (r/atom [])) ;; Vector of maps containing keys: ::css
(def custom-classes-in-use (r/atom [])) ;; Vector of maps containing keys: ::css

(def ^:private stylefy-root-node (atom nil))
(def ^:private stylefy-node-id :#_stylefy-styles_)
(def ^:private stylefy-constant-node-id :#_stylefy-constant-styles_)
(def ^:private dom-update-requested? (atom false))

(defn- style-by-hash [style-hash]
  (when style-hash
    (get @styles-in-use style-hash)))

(defn- update-style-tags!
  [node node-constant]
  (let [styles-in-css (map (fn [style-hash]
                             (::css (style-by-hash style-hash)))
                           (keys @styles-in-use))
        keyframes-in-css (map (fn [keyframes]
                                (::css keyframes))
                              @keyframes-in-use)
        font-faces-in-use (map (fn [properties]
                                 (::css properties))
                               @font-faces-in-use)
        custom-tags-in-use (map (fn [tag-definition]
                                  (::css tag-definition))
                                @custom-tags-in-use)
        custom-classes-in-use (map (fn [tag-definition]
                                     (::css tag-definition))
                                   @custom-classes-in-use)]
    (dommy/set-text! node-constant (apply str (concat font-faces-in-use
                                                      keyframes-in-css
                                                      custom-tags-in-use
                                                      custom-classes-in-use)))
    (dommy/set-text! node (apply str styles-in-css))))

(defn- mark-all-styles-added-in-dom! []
  (reset! styles-in-use (apply merge (map
                                       #(-> {% (assoc (get @styles-in-use %) ::in-dom? true)})
                                       (keys @styles-in-use)))))

(defn- get-stylefy-node [id]
  (if (nil? @stylefy-root-node)
    (dommy/sel1 id)
    (dommy/sel1 @stylefy-root-node id)))

(defn- update-styles-in-dom!
  "Updates style tag if needed."
  []
  (when @dom-update-requested?
    (let [node (get-stylefy-node stylefy-node-id)
          node-constant (get-stylefy-node stylefy-constant-node-id)]
      (if (and node node-constant)
        (do (update-style-tags! node node-constant)
            (reset! dom-update-requested? false)

            (try
              (cache/cache-styles (apply merge
                                         (map
                                           #(-> {% (dissoc (get @styles-in-use %) ::in-dom?)})
                                           (keys @styles-in-use))))
              (catch :default e
                (.warn js/console (str "Unable to cache styles, error: " e))
                (cache/clear-styles)
                e))

            (mark-all-styles-added-in-dom!))
        (.error js/console "stylefy is unable to find the required <style> tags!")))))

(defn- asynchronously-update-dom
  "Updates style tag if needed."
  []
  (when-not @dom-update-requested?
    (reset! dom-update-requested? true)
    (go
      (update-styles-in-dom!))))

(defn check-stylefy-initialisation []
  (when-not @stylefy-initialised?
    (.warn js/console (str "stylefy has not been initialised correctly. Call stylefy/init once when your application starts."))))

(defn init-stylefy-root-node [options]
  (when (:stylefy-root-node options)
    (reset! stylefy-root-node (:stylefy-root-node options))))

(defn init-cache [options]
  (when (not= (:use-caching? options) false)
    (cache/use-caching! (:cache-options options))

    (when-let [cached-styles (cache/read-cache-value
                               cache/cache-key-styles)]
      (reset! styles-in-use (or cached-styles {}))
      (asynchronously-update-dom)
      (update-styles-in-dom!))))

(defn- save-style!
  "Stores the style in an atom. The style is going to be added into the DOM soon."
  [{:keys [props hash] :as style}]
  (assert props "Unable to save empty style!")
  (assert hash "Unable to save style without hash!")
  (let [style-css (conversion/style->css style)
        style-to-be-saved {::css style-css}]
    (swap! styles-in-use assoc hash style-to-be-saved)
    (asynchronously-update-dom)))

(defn style-in-dom? [style-hash]
  (boolean (::in-dom? (style-by-hash style-hash))))

(defn add-keyframes [identifier & frames]
  (let [garden-definition (apply at-keyframes identifier frames)]
    (swap! keyframes-in-use conj {::css (css garden-definition)})
  (asynchronously-update-dom)
  garden-definition))

(defn add-font-face [properties]
  (let [garden-definition (at-font-face properties)]
    (swap! font-faces-in-use conj {::css (css garden-definition)})
    (asynchronously-update-dom)
    garden-definition))

(defn add-tag [name properties]
  (let [custom-tag-definition {::tag-name name ::tag-properties properties}]
    (swap! custom-tags-in-use conj {::css (conversion/style->css
                                            {:props (::tag-properties custom-tag-definition)
                                             :custom-selector (::tag-name custom-tag-definition)})})
    (asynchronously-update-dom)
    custom-tag-definition))

(defn add-class [name properties]
  (let [custom-class-definition {::class-name name ::class-properties properties}]
    (swap! custom-classes-in-use conj {::css (conversion/style->css
                                               {:props (::class-properties custom-class-definition)
                                                :custom-selector (conversion/class-selector (::class-name custom-class-definition))})})
    (asynchronously-update-dom)
    custom-class-definition))