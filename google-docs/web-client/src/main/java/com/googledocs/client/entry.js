/**
 * J2CL / Closure entry point for the browser OT client.
 *
 * Requiring the Java class keeps it (and everything it reaches in ot-core) alive through
 * ADVANCED_OPTIMIZATIONS, and exportSymbol publishes it as the stable global `window.OtClient`
 * that the vanilla-JS renderer (static/app.js) uses.
 */
goog.module('googledocs.client.entry');

const OtClient = goog.require('com.googledocs.client.OtClient');

goog.exportSymbol('OtClient', OtClient);
