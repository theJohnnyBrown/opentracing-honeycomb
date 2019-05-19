# opentracing-honeycomb

An implementation of the [OpenTracing java API](https://opentracing.io/), which
sends trace and span data to [honeycomb.io](https://www.honeycomb.io/). I wrote
this because I wanted to use honeycomb.io to instrument a pedestal application.

## Usage

### Install

Add `[opentracing-honeycomb "0.1.0-SNAPSHOT"]` to your leiningen dependencies

### Use

```clojure
(ns myapplication.core
  (:require [opentracing-honeycomb :refer get-tracer]))

;

(def tracer
  (get-tracer "tracing-example-dev" ;; service name
    {:data-set "myapplication-dev"  ;; honeycomb dataset
     :write-key "..."}))
```

`tracer` is an object which implements the `io.opentracing.Tracer` interface and
sends trace and span data to honeycomb.

## License

Copyright © 2019 Jonathan Brown

This program and the accompanying materials are made available under the
terms of the Eclipse Public License 2.0 which is available at
http://www.eclipse.org/legal/epl-2.0.

This Source Code may also be made available under the following Secondary
Licenses when the conditions for such availability set forth in the Eclipse
Public License, v. 2.0 are satisfied: GNU General Public License as published by
the Free Software Foundation, either version 2 of the License, or (at your
option) any later version, with the GNU Classpath Exception which is available
at https://www.gnu.org/software/classpath/license.html.
