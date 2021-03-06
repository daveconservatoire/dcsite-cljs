(ns daveconservatoire.audio.core-cards
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [devcards.core :refer-macros [defcard deftest]]
            [cljs.core.async :as async :refer [promise-chan chan <! >! close! put! alts!]]
            [cljs.test :refer-macros [is are testing async]]
            [cljs.spec.test.alpha :as st]
            [clojure.test.check]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties]
            [goog.object :as gobj]
            [om.dom :as dom]
            [om.next :as om :include-macros true]
            [daveconservatoire.audio.core :as audio]
            [clojure.string :as str]
            [cljs.spec.alpha :as s]))

(deftest test-play-piano
  (is (= 1 1)))

(defn pattern-buffers [pattern]
  (let [library @audio/*sound-library*]
    (mapv (fn [x] (if (string? x)
                    (get library x)
                    x))
          pattern)))

(defn sp-stop [c]
  (if-let [process (om/get-state c :process)]
    (put! process :stop)))

(defn reset-play [c {:keys [pattern]}]
  (sp-stop c)
  (om/update-state! c assoc :process
                    (audio/consume-loop 10 (audio/loop-chan (pattern-buffers pattern)
                                                            (audio/current-time)
                                                            (chan 8)))))

(om/defui SoundPattern
  Object
  (componentDidMount [this]
    (reset-play this (om/props this)))

  (componentWillUnmount [this]
    (sp-stop this))

  (componentWillReceiveProps [this new-props]
    (reset-play this new-props))

  (render [_]
    (dom/noscript nil)))

(def sound-pattern (om/factory SoundPattern))

(defn chord [base-note]
  (let [st (audio/note->semitone base-note)
        chord (->> [st (+ st 4) (+ st 7)]
                   (mapv audio/semitone->note))]
    chord))

(defn tick-pattern [base-note i]
  (let [st (audio/note->semitone base-note)
        chord (->> [st (+ st 4) (+ st 7)]
                   (mapv audio/semitone->note))]
    (concat chord [i "low" i "low" i "low" i])))

(defn debounce [in ms]
  (let [out (chan)]
    (go-loop [last-val nil]
      (let [val (if (nil? last-val) (<! in) last-val)
            timer (async/timeout ms)
            [new-val ch] (alts! [in timer])]
        (condp = ch
          timer (do (>! out val) (recur nil))
          in (if new-val (recur new-val)))))
    out))

(om/defui DebouncedSlider
  Object
  (initLocalState [_]
    {:chan (chan (async/sliding-buffer 10))})

  (componentDidMount [this]
    (let [{:keys [on-change]
           :or   {on-change identity}} (om/props this)]
      (go-loop []
        (when-let [v (<! (debounce (om/get-state this :chan) 300))]
          (on-change (js/parseInt v))
          (recur)))))

  (componentWillUnmount [this]
    (close! (om/get-state this :chan)))

  (render [this]
    (let [props (om/props this)
          chan (om/get-state this :chan)]
      (dom/input (clj->js (assoc props :onChange #(put! chan (.. % -target -value))
                                       :type "range"))))))

(def debounce-slider (om/factory DebouncedSlider))

(om/defui ChordMetronome
  Object
  (initLocalState [_]
    {:note     nil
     :interval 60})

  (render [this]
    (let [{:keys [note interval]} (om/get-state this)]
      (dom/div nil
        interval
        (for [n [nil "C3" "D3" "E3" "F3" "G3" "A3" "B3"]]
          (dom/button #js {:onClick #(om/update-state! this assoc :note n)
                           :style   (if (= n note)
                                      #js {:background "#0c0"})
                           :key     n}
            (or n "Mute")))
        (debounce-slider {:react-key "debounce" :min 30 :max 320 :value interval :on-change #(om/update-state! this assoc :interval %)})
        #_(let [i 1.5]
            (sound-pattern {:react-key "pattern" :pattern (flatten [(chord "C3") i
                                                                    (chord "G3") i
                                                                    "A3" "C4" "E4" i
                                                                    (chord "F3") i])}))
        (if note
          (sound-pattern {:react-key "pattern" :pattern (tick-pattern note (/ 60 interval))}))))))

(def chord-metronome (om/factory ChordMetronome))

(defcard chord-metronome-card
  (fn [_ _]
    (chord-metronome {})))

(defcard chord-example
  (for [note ["C3" "A3"]
        [k v] audio/MAJOR-ARRANGEMENTS
        :let [base (audio/note->semitone note)
              increment (audio/MAJOR-STEPS k)]]
    (audio/chord (+ base increment) v)))

(defcard progression
  (audio/major-chord-progression "C3" (map dec [1 5 6 4])))

(deftest test-note->semitone
  (are [note semitone] (= (audio/note->semitone note) semitone)
    30 30
    "A0" 0
    "C3" 27
    "D3" 29
    "D#4" 42
    "C8" 87)

  (is (= (st/result-type (st/check `audio/note->semitone)) :check-passed)))

(deftest test-semitone->note
  (are [note semitone] (= (audio/semitone->note note) semitone)
    "C3" "C3"
    27 "C3"
    29 "D3"
    42 "Eb4")

  (is (= (st/result-type (st/check `audio/semitone->note)) :check-passed)))

(defcard test-play-regular-sequence
  (fn [_ _]
    (dom/button #js {:onClick (fn []
                                (let [nodes (->> ["C4" "D4" "E4" "F4"]
                                                 (into [] (map (fn [s]
                                                                 {::audio/node-gen (->> s audio/semitone->note (get @audio/*sound-library*))}))))]
                                  (audio/play-regular-sequence nodes {::audio/time     (audio/current-time)
                                                                      ::audio/interval 1})))}
      "Play")))

(defcard test-play-note-sequence
  (fn [_ _]
    (let [anodes (atom [])]
      (dom/div nil
        (dom/button #js {:onClick (fn []
                                    (let [nodes (->> [["C4" 0.5] ["G4" 1] ["F4" 0.3] ["E4" 0.3] ["D4" 0.3] ["Db4" 0.6] ["D4" 0]]
                                                     (into [] (map (fn [[s d]]
                                                                     {::audio/node-gen (->> s audio/semitone->note (get @audio/*sound-library*))
                                                                      ::audio/duration d}))))]
                                      (reset! anodes (audio/play-sequence nodes {::audio/time (audio/current-time)}))))}
          "Play")
        (dom/button #js {:onClick #(run! audio/stop @anodes)} "Stop")))))

(defcard test-play-sound-file
  (fn [_ _]
    (dom/div nil
      (dom/button #js {:onClick (fn []
                                  (go
                                    (let [buffer (<! (audio/load-sound-file "/audio/2a"))]
                                      (audio/play {::audio/node-gen #(audio/buffer-node buffer)
                                                   ::audio/time     (audio/current-time)}))))}
        "Play")
      (dom/button #js {:onClick #(audio/global-stop-all)} "Stop"))))

(defcard test-play-sound-library
  (fn [_ _]
    (dom/div nil
      (dom/button #js {:onClick (fn []
                                  (go
                                    (let [buffers (<! (audio/load-sound-library {:a0 "/audio/0a"
                                                                                 :a1 "/audio/1a"
                                                                                 :a2 "/audio/2a"}))
                                          t       (audio/current-time)]
                                      (audio/play {::audio/node-gen #(audio/buffer-node (:a0 buffers))
                                                   ::audio/time     t})
                                      (audio/play {::audio/node-gen #(audio/buffer-node (:a1 buffers))
                                                   ::audio/time     (+ t 1)}))))}
        "Play")
      (dom/button #js {:onClick #(audio/global-stop-all)} "Stop"))))
