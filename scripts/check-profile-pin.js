'use strict';
/*
 * One PIN rule across three surfaces.
 *
 * A profile PIN is 4 to 8 digits when it is set. For verification the gate reads the pack's
 * `pin.length` (4 in every shipped pack) and submits itself the moment that many digits are in —
 * the web pad already does the same for a fixed-length profile PIN, so a viewer never has to walk
 * to OK after the last number. A shorter number stored by an older client must still open its own
 * gate so it can be replaced: OK stays available for 1-8 digits, and the pad keeps accepting up to
 * eight rather than stopping at the themed length.
 */

const assert = require('assert');
const fs = require('fs');
const path = require('path');

const root = path.join(__dirname, '..');
const read = (relative) => fs.readFileSync(path.join(root, relative), 'utf8');

const pin = read('app/src/main/java/com/scenix/tv/PinActivity.kt');
assert.match(pin, /if \(pin\.length < 8\) \{/,
  'the profile gate must accept up to eight digits, not stop at four');
assert.match(pin, /token\("pin", "length", 4\.0\)/,
  'the gate must read its submit length from the pack, not hard-code four');
assert.match(pin, /if \(pin\.length == pinLength\) submit\(\)/,
  'the gate must submit itself when the pack PIN length is reached');
assert.match(pin, /if \(busy\) return/,
  'a submission in flight must block a second one from fast typing');
assert.match(pin, /if \(pin\.isEmpty\(\) \|\| pin\.length > 8 \|\| busy\) return/,
  'OK must accept a short legacy number (at least one digit) and refuse more than eight');
assert.match(pin, /Wrong PIN, try again/,
  'a refusal must be said plainly on the pad');
assert.match(pin, /it enters itself/,
  'the gate must tell the viewer that the last digit submits the PIN');

const hub = read('hub/index.js');
assert.match(hub, /if \(v && \(v\.length < 4 \|\| v\.length > 8\)\) \{[\s\S]*?a profile PIN is 4 to 8 digits/,
  'the hub must refuse a new profile PIN outside 4-8 digits, whatever the client sends');

const editor = read('hub/web/js/profileedit.js');
assert.match(editor, /ctx\.enterPin\(profile, current \? 'Choose a new PIN' : 'Choose a PIN', \{ minLength: 4, maxLength: 8 \}\)/,
  'the browser must ask for 4-8 digits when setting a profile PIN');
assert.match(editor, /Enter \$\{p\.name\}’s current PIN`, \{ minLength: 1, maxLength: 8 \}/,
  'but it must accept a shorter stored number when proving one');

const add = read('hub/web/js/screens/add.js');
assert.match(add, /enterPin\(\{ name \}, 'Choose a 4 to 8 digit PIN', \{ minLength: 4, maxLength: 8 \}\)/,
  'a profile created with a PIN must use the same 4-8 rule');

console.log('PROFILE PIN CONTRACT OK');
