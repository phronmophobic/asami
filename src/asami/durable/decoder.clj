(ns ^{:doc "Encodes and decodes data for storage. Clojure implementation"
      :author "Paula Gearon"}
    asami.durable.decoder
    (:require [clojure.string :as s]
              [asami.graph :as graph]
              [asami.durable.common :refer [read-byte read-bytes read-short]]
              [asami.durable.codec :refer [byte-mask data-mask sbytes-shift len-nybble-shift utf8
                                           long-type-code date-type-code inst-type-code
                                           sstr-type-code skey-type-code node-type-code
                                           boolean-false-bits boolean-true-bits]])
    (:import [clojure.lang Keyword BigInt]
             [java.math BigInteger BigDecimal]
             [java.net URI]
             [java.time Instant]
             [java.util Date UUID]
             [java.nio ByteBuffer]
             [java.nio.charset Charset]))

;; (set! *warn-on-reflection* true)

(defn decode-length
  "Reads the header to determine length.
  ext: if true (bit is 0) then length is a byte, if false (bit is 1) then length is in either a short or an int
  pos: The beginning of the data. This has skipped the type byte.
  returns: a pair of the header length and the data length."
  [ext paged-rdr ^long pos]
  (if ext
    [Byte/BYTES (bit-and 0xFF (read-byte paged-rdr pos))]
    (let [len (read-short paged-rdr pos)]
      (if (< len 0)
        (let [len2 (read-short paged-rdr pos)]
          [Integer/BYTES (bit-or
                          (bit-shift-left (int (bit-and 0x7FFF len)) Short/SIZE)
                          len2)])
        [Short/BYTES len]))))

(defn decode-length-node
  "Reads the header to determine length.
  data: The complete buffer to decode, including the type byte.
  returns: the length, or a lower bound on the length"
  [^bytes data]
  (let [b0 (aget data 0)]
    (cond ;; test for short format objects
      (zero? (bit-and 0x80 b0)) b0                ;; short string
      (zero? (bit-and 0x40 b0)) (bit-and 0x3F b0) ;; short URI
      (zero? (bit-and 0x20 b0)) (bit-and 0x0F b0) ;; short keyword OR number
      ;; First byte contains only the type information. Give a large number = 63
      :default 0x3F)))

;; Readers are given the length and a position. They then read data into a type

(defn read-str
  [paged-rdr ^long pos ^long len]
  (String. ^bytes (read-bytes paged-rdr pos len) ^Charset utf8))

(defn read-uri
  [paged-rdr ^long pos ^long len]
  (URI/create (read-str paged-rdr pos len)))

(defn read-keyword
  [paged-rdr ^long pos ^long len]
  (keyword (read-str paged-rdr pos len)))

(defn read-long
  "Raw reading of big-endian bytes into a long"
  ^long [paged-rdr ^long pos ^long len]
  (let [^bytes b (read-bytes paged-rdr pos len)]
    (areduce b i ret 0 (bit-or (bit-shift-left ret Byte/SIZE) (bit-and 0xFF (aget b i))))))

;; decoders operate on the bytes following the initial type byte information
;; if the data type has variable length, then this is decoded first

(defn long-decoder
  [ext paged-rdr ^long pos]
  (let [b (ByteBuffer/wrap (read-bytes paged-rdr pos Long/BYTES))]
    [(.getLong b 0) Long/BYTES]))

(defn double-decoder
  [ext paged-rdr ^long pos]
  (let [b (ByteBuffer/wrap (read-bytes paged-rdr pos Long/BYTES))]
    [(.getDouble b 0) Long/BYTES]))

(defn string-decoder
  [ext paged-rdr ^long pos]
  (let [[i len] (decode-length ext paged-rdr pos)]
    [(read-str paged-rdr (+ pos i) len) (+ i len)]))

(defn uri-decoder
  [ext paged-rdr ^long pos]
  (let [[i len] (decode-length ext paged-rdr pos)]
    [(read-uri paged-rdr (+ pos i) len) (+ i len)]))

(defn bigint-decoder
  [ext paged-rdr ^long pos]
  (let [[i len] (decode-length ext paged-rdr pos)
        b (read-bytes paged-rdr (+ i pos) len)]
    [(bigint (BigInteger. ^bytes b)) (+ i len)]))

(defn bigdec-decoder
  [ext paged-rdr ^long pos]
  (let [[s len] (string-decoder ext paged-rdr pos)]
    [(bigdec s) len]))

(defn date-decoder
  [ext paged-rdr ^long pos]
  [(Date. ^long (first (long-decoder ext paged-rdr pos))) Long/BYTES])

(def ^:const instant-length (+ Long/BYTES Integer/BYTES))

(defn instant-decoder
  [ext paged-rdr ^long pos]
  (let [b (ByteBuffer/wrap (read-bytes paged-rdr pos instant-length))
        epoch (.getLong b 0)
        sec (.getInt b Long/BYTES)]
    [(Instant/ofEpochSecond epoch sec) instant-length]))

(defn keyword-decoder
  [ext paged-rdr ^long pos]
  (let [[i len] (decode-length ext paged-rdr pos)]
    [(read-keyword paged-rdr (+ pos i) len) (+ i len)]))

(def ^:const uuid-length (* 2 Long/BYTES))

(defn uuid-decoder
  [ext paged-rdr ^long pos]
  (let [b (ByteBuffer/wrap (read-bytes paged-rdr pos uuid-length))
        low (.getLong b 0)
        high (.getLong b Long/BYTES)]
    [(UUID. high low) uuid-length]))

(defn blob-decoder
  [ext paged-rdr ^long pos]
  (let [[i len] (decode-length ext paged-rdr pos)]
    [(read-bytes paged-rdr (+ i pos) len) (+ i len)]))

(defn xsd-decoder
  [ext paged-rdr ^long pos]
  (let [[s len] (string-decoder ext paged-rdr pos)
        sp (s/index-of s \space)]
    [[(URI/create (subs s 0 sp)) (subs (inc sp))] len]))

(defn default-decoder
  "This is a decoder for unsupported data that has a string constructor"
  [ext paged-rdr ^long pos]
  (let [[s len] (string-decoder ext paged-rdr pos)
        sp (s/index-of s \space)
        class-name (subs s 0 sp)]
    (try
      (let [c (Class/forName class-name) 
            cn (.getConstructor c (into-array Class [String]))]
        [(.newInstance cn (object-array [(subs s (inc sp))])) len])
      (catch Exception e
        (throw (ex-info (str "Unable to construct class: " class-name) {:class class-name}))))))

(declare typecode->decoder read-object-size)

(defn seq-decoder
  "This is a decoder for sequences of data. Use a vector as the sequence."
  [ext paged-rdr ^long pos]
  ;; read the length of the header and the length of the seq data
  (let [[i len] (decode-length ext paged-rdr pos)
        start (+ i pos)
        end (+ start len)
        ;; get the 0 byte. This contain info about the types in the seq
        b0 (read-byte paged-rdr start)
        decoder (if (zero? b0)
                  ;; heterogeneous types. Full header on every element. Read objects with size.
                  read-object-size
                  ;; homogeneous types. The header is only written once
                  (if (= 0xD0 (bit-and 0xF0 b0))      ;; homogenous numbers
                    (let [num-len (bit-and 0x0F b0)]  ;; get the byte length of all the numbers
                      ;; return a function that deserializes the number and pairs it with the length
                      #(vector (read-long %1 %2 num-len) num-len))
                    (if-let [tdecoder (typecode->decoder (bit-and 0x0F b0))] ;; reader for type
                      ;; the standard decoder already returns a deserialized value/length pair
                      #(tdecoder true %1 %2)
                      (throw (ex-info "Illegal datatype in array" {:type-code (bit-and 0x0F b0)})))))]
    ;; iterate over the buffer deserializing until the end is reached
    (loop [s [] offset (inc start)]
      (if (>= offset end)
        [s (+ i len)]  ;; end of the buffer, return the seq and the number of bytes read
        (let [[o obj-len] (decoder paged-rdr offset)]  ;; deserialize, then step forward
          (recur (conj s o) (+ offset obj-len)))))))

(defn map-decoder
  "A decoder for maps. Returns the map and the bytes read."
  [ext paged-rdr ^long pos]
  ;; read the map as one long seq, then split into pairs
  (let [[s len] (seq-decoder ext paged-rdr pos)
        m (into {} (map vec (partition 2 s)))]
    [m len]))

(def typecode->decoder
  "Map of type codes to decoder functions. Returns object and bytes read."
  {0 long-decoder
   1 double-decoder
   2 string-decoder
   3 uri-decoder
   4 seq-decoder
   5 map-decoder
   6 bigint-decoder
   7 bigdec-decoder
   8 date-decoder
   9 instant-decoder
   10 keyword-decoder
   11 uuid-decoder
   12 blob-decoder
   13 xsd-decoder})

(def ^:const type-nybble-shift 60)

(def ^:const nybble-mask 0xF)
(def ^:const long-nbit  0x0800000000000000)
(def ^:const lneg-bits -0x1000000000000000) ;; 0xF000000000000000

(defn extract-long
  "Extract a long number from an encapsulating ID"
  ^long [^long id]
  (let [l (bit-and data-mask id)]
    (if (zero? (bit-and long-nbit l))
      l
      (bit-or lneg-bits l))))

(defn as-byte
  [n]
  (if (zero? (bit-and 0x80 n))
    (byte n)
    (byte (bit-or -0x100 n))))

(defn extract-sstr
  "Extract a short string from an encapsulating ID"
  [^long id]
  (let [len (bit-and (bit-shift-right id len-nybble-shift) nybble-mask)
        abytes (byte-array len)]
    (doseq [i (range len)]
      (aset abytes i
            (->> (* i Byte/SIZE)
                 (- sbytes-shift)
                 (bit-shift-right id)
                 (bit-and byte-mask)
                 as-byte
                 byte)))
    (String. ^bytes abytes 0 len ^Charset utf8)))

(defn extract-node
  [id]
  (asami.graph.InternalNode. (bit-and data-mask id)))

(defn unencapsulate-id
  "Converts an encapsulating ID into the object it encapsulates. Return nil if it does not encapsulate anything."
  [^long id]
  (when (> 0 id)
    (case id
      -0x5000000000000000 false                          ;; boolean-false-bits
      -0x4800000000000000 true                           ;; boolean-true-bits
      (let [tb (bit-and (bit-shift-right id type-nybble-shift) nybble-mask)]
        (case tb
          0x8 (extract-long id)                          ;; long-type-code
          0xC (Date. (extract-long id))                  ;; date-type-code
          0xA (Instant/ofEpochMilli (extract-long id))   ;; inst-type-code
          0xE (extract-sstr id)                          ;; sstr-type-code
          0x9 (keyword (extract-sstr id))                ;; skey-type-code
          0xD (extract-node id)                          ;; node-type-code
          nil)))))

(defn encapsulated-node?
  [^long id]
  (let [top-nb (bit-and (bit-shift-right id type-nybble-shift) nybble-mask)]
    (or (= top-nb skey-type-code) (= top-nb node-type-code))))

(defn type-info
  "Returns the type information encoded in a header-byte"
  [b]
  (cond
    (zero? (bit-and 0x80 b)) 2   ;; string
    (zero? (bit-and 0x40 b)) 3   ;; uri
    (zero? (bit-and 0x20 b)) 10  ;; keyword
    ;; if short uris are permitted in the future then change to the URI code (3) here
    :default (bit-and 0xF b)))

(defn partials-len
  "Determine the number of bytes that form a partial character at the end of a UTF-8 byte array.
  The len argument is the defined length of the full string, but that may be greater than the bytes provided."
  ([^bytes bs] (partials-len bs (alength bs)))
  ([^bytes bs len]
   (let [end (dec (min len (alength bs)))]
     (when (>= end 0)
       (loop [t 0]
         (if (= 4 t)  ;; Safety limit. Should not happen for well formed UTF-8
           t
           (let [b (aget bs (- end t))]
             (if (zero? (bit-and 0x80 b))  ;; single char that can be included
               t
               (if (zero? (bit-and 0x40 b))  ;; extension char that may be truncated
                 (recur (inc t))
                 (cond
                   (= 0xC0 (bit-and 0xE0 b)) (if (= 1 t) 0 (inc t)) ;; 2 bytes
                   (= 0xE0 (bit-and 0xF0 b)) (if (= 2 t) 0 (inc t)) ;; 3 bytes
                   (= 0xF0 (bit-and 0xF8 b)) (if (= 3 t) 0 (inc t)) ;; 4 bytes
                   :default (recur (inc t))))))))))))  ;; this should not happen for well formed UTF-8

(defn string-style-compare
  "Compare the string form of an object with bytes that store the string form of an object"
  [left-s ^bytes right-bytes]
  (let [rbc (alength right-bytes) ;; length of all bytes
        full-length (decode-length-node right-bytes)
        ;; get the length of the bytes used in the string
        rlen (min full-length (dec rbc))
        ;; look for partial chars to be truncated, starting at the end.
        ;; string starts 1 byte in, after the header, so start at inc of the string byte length
        trunc-len (partials-len right-bytes (inc rlen))
        right-s (String. right-bytes 1 (int (- rlen trunc-len)) ^Charset utf8)
        ;; only truncate the LHS if the node does not contain all of the string data
        left-side (if (<= full-length (dec rbc))
                    left-s
                    (subs left-s 0 (min (count left-s) (count right-s))))]
    (compare left-side right-s)))

(defn long-bytes-compare
  "Compare data from 2 values that are the same type. If the data cannot give a result
   then return 0. Operates on an array, expected to be in an index node."
  [type-left left-header left-body left-object right-bytes]
  (case (byte type-left)
    2 (string-style-compare left-object right-bytes)   ;; String
    3 (string-style-compare (str left-object) right-bytes)  ;; URI
    10 (string-style-compare (subs (str left-object) 1) right-bytes)  ;; Keyword
    ;; otherwise, skip the type byte in the right-bytes, and raw compare left bytes to right bytes
    (or
     (first (drop-while zero? (map compare left-body (drop 1 right-bytes)))) ;; includes right header and body
     0)))

(defn read-object-size
  "Reads an object from a paged-reader, at id=pos. Returns both the object and it's length."
  [paged-rdr ^long pos]
  (let [b0 (read-byte paged-rdr pos)
        ipos (inc pos)]
    (cond ;; test for short format objects
      ;; calculate the length for short format objects, and increment by 1 to include the intro byte
      (zero? (bit-and 0x80 b0)) [(read-str paged-rdr ipos b0) (inc b0)]
      (zero? (bit-and 0x40 b0)) (let [len (bit-and 0x3F b0)]
                                  [(read-uri paged-rdr ipos len) (inc len)])
      ;; First byte contains only the type information. Increment the returned length to include b0
      (= 0xE0 (bit-and 0xE0 b0)) (update ((typecode->decoder (bit-and 0x0F b0) default-decoder)
                                          (zero? (bit-and 0x10 b0)) paged-rdr ipos)
                                         1 inc)
      ;; high nybble is 1100 for keywords or 1101 for long number
      :default (let [read-fn (if (zero? (bit-and 0x30 b0)) read-keyword read-long)
                     len (bit-and 0x0F b0)]
                 [(read-fn paged-rdr ipos len) (inc len)]))))

(defn read-object
  "Reads an object from a paged-reader, at id=pos"
  [paged-rdr ^long pos]
  (first (read-object-size paged-rdr pos)))

;; the test for zero here is the y bit described in asami.durable.codec
;; This may need to change if the y bit is repurposed.
