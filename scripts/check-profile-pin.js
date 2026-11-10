'use strict';
/*
 * One PIN rule across three surfaces.
 *
 * A profile PIN is 4 to 8 digits. A shorter number stored by an older client must still open its own
 * gate so it can be replaced — one profile was left with a single digit and the television's keypad
 * stopped at four, so the owner could never enter it again.
 */

const assert = require('assert');
const fs = require('fs');
const path = require('path');

const root = path.join(__dirname, '..');
const read = (relative) => fs.readFileSync(path.join(root, relative), 'utf8');

const pin = read('app/src/main/java/com/scenix/tv/PinActivity.kt');
assert.match(pin, /if \(pin\.length < 8\) pin\.append\(label\)/,
  'the profile gate must accept up to eight digits, not stop at four');
assert.doesNotMatch(pin, /if \(pin\.length == 4\) submit\(\)/,
  'the gate must not submit at four digits: a 5-8 digit PIN would be cut short');
assert.match(pin, /if \(pin\.isEmpty\(\) \|\| pin\.length > 8 \|\| busy\) return/,
  'OK must accept a short legacy number (at least one digit) and refuse more than eight');
assert.match(pin, /4 to 8 digits · OK to confirm/,
  'the gate must say how a PIN is entered');

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
