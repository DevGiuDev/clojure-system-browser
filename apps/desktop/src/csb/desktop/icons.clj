(ns csb.desktop.icons)

(def ^:private paths
  {:folder "M2 12.5V4h4l2 2h6v2H4l-2 4.5h10L15 8"
   :search "M11 11l3 3M12 7a5 5 0 1 1 -10 0a5 5 0 1 1 10 0"
   :save "M2 2h9l3 3v9H2zM5 2v4h6V2M5 14v-5h6v5"
   :undo "M6 3L2 7l4 4M2 7h8a4 4 0 0 1 4 4v2"
   :reload "M14 7a6 6 0 1 0 -1.5 5M14 2v5H9"
   :order "M2 3h1v2M2 7h2l-2 3h2M2 12h2v2H2M7 4h7M7 8h7M7 12h7"
   :tests "M6 2h4M7 2v5l-4.5 6a1 1 0 0 0 .8 1.5h9.4a1 1 0 0 0 .8 -1.5L9 7V2M5 10h6"
   :terminal "M2 3h12v10H2zM4 6l2 2-2 2M8 10h3"
   :layers "M2 5l6-3 6 3-6 3zM2 8l6 3 6-3M2 11l6 3 6-3"
   :categories "M2 3h12l-5 5v5l-2 1V8z"
   :definition "M5 3L2 8l3 5M11 3l3 5-3 5M9 3L7 13"
   :function "M9 2H8a2 2 0 0 0-2 2v8a2 2 0 0 1-2 2H3M3 7h6M10 9l4 4m0-4-4 4"
   :macro "M8 2l1.4 4.6L14 8l-4.6 1.4L8 14l-1.4-4.6L2 8l4.6-1.4z"
   :value "M2 6l6-4 6 4v5l-6 3-6-3zM2 6l6 3 6-3M8 9v5"
   :file "M3 2h6l4 4v8H3zM9 2v4h4M6 9h4M6 12h4"
   :lock "M5 7V5a3 3 0 0 1 6 0v2M3 7h10v7H3zM8 10v1"
   :pencil "M3 10l8-8 3 3-8 8-4 1zM9 4l3 3"
   :warning "M8 2l6.5 12h-13zM8 6v3m0 2v.5"
   :error "M14 8a6 6 0 1 1-12 0a6 6 0 1 1 12 0M6 6l4 4m0-4-4 4"
   :success "M3 8l3 3 7-7"
   :clock "M14 8a6 6 0 1 1-12 0a6 6 0 1 1 12 0M8 4v4l3 2"
   :idle "M12 8a4 4 0 1 1-8 0a4 4 0 1 1 8 0"})

(defn graphic [icon]
  {:fx/type :stack-pane :style-class ["desktop-icon"] :mouse-transparent true
   :min-width 16 :pref-width 16 :max-width 16
   :min-height 16 :pref-height 16 :max-height 16
   :children [{:fx/type :svg-path :style-class ["icon-stroke"]
               :content (paths icon)}]})
