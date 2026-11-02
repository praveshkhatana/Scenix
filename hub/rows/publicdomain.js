'use strict';
/*
 * rows/publicdomain.js — the bundled legal shelf: films that are public domain in the U.S.,
 * streamed directly from the Internet Archive by providers/public-domain.js.
 *
 * The row names its provider through ctx.catalog(), never by importing it. That is the plugin
 * property the rest of the hub relies on: disable Public Domain Movies in Settings and this
 * build returns [], /home drops the empty row, and no other shelf changes. A missing or broken
 * provider is an empty row, never a broken home screen.
 *
 * order 16 puts it just under the billboard and the personal shelves, so a fresh install has
 * something to watch before anyone has installed a source or built a profile history.
 */
module.exports = {
  id: 'public-domain',
  title: 'Public Domain Movies',
  card: 'poster',
  order: 16,
  scope: 'all',
  max: 30,

  async build(ctx) {
    return ctx.catalog('public-domain', 'public-domain');
  },
};
