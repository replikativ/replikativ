# replikativ

[replikativ](http://replikativ.io) is an eventual consistent synchronisation system. It is build with [Clojure](https://clojure.org)/[Script](https://clojurescript.org) and can synchronize between different runtimes including the browser, nodejs and the JVM. This package provides JavaScript bindings for replikativ for both browser and [Node.js](https://nodejs.org). At the moment we support only clientPeers for Node.js but a server peer is planned for the next release.

## Installation

Assuming that you’re using [npm](https://www.npmjs.com) package manager with a module bundler like [webpack](https://webpack.github.io) to consume ES6 modules.

~~~
npm install --save replikativ
~~~

If you don't use a modern module bundler, you can grab the latest compiled version on [github](https://github.com/replikativ/replikativ/releases/tag/js0.2.7).

## Documentation

- a simple introduction in plain javascript: [Counter Tutorial](https://github.com/replikativ/replikativ/blob/master/npm/doc/tutorial.md)
- basic documentation for all API calls: [API](https://github.com/replikativ/replikativ/blob/master/npm/doc/api.md)

For further examples or questions, please take a look at the [project site](http://replikativ.io) or ask on [gitter](https://gitter.im/replikativ/replikativ).
