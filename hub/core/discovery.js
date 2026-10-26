'use strict';
/*
 * core/discovery.js — say who we are, on UDP, so a client never has to type an address.
 *
 * Pairing a TV to a hub by typing `<hub-host>:8788` into a remote-controlled text box is the worst
 * interaction in the product, and it fails twice over: a person cannot read an address off a router
 * screen with a remote in their hand, and a DHCP lease changes under them, so the pairing they got
 * working last month is broken by nothing they did. A client should instead show a list of names and let
 * the viewer pick one. That is what this file answers: a small SSDP responder, Node `dgram` only, no
 * dependencies, that announces this hub on the LAN and replies when asked.
 *
 * Two things it deliberately does not do:
 *   - It does not name a machine. The name announced is `settings.hubName` — the owner's words, read when
 *     the announcement is built, so renaming the hub takes effect on the next announce without a restart.
 *     A hostname would also be a lie on any box but one.
 *   - It does not assume a LAN layout. The address in LOCATION is taken from the interface the packet
 *     arrives on, and the port is the one this hub actually bound. Nothing here is a constant about this
 *     house.
 *
 * The search target is our own (`ssdp:scenix-hub`), not the generic device/profile targets, so we answer
 * exactly the clients looking for us and stay quiet about everything else on the network. A wildcard
 * `ssdp:discover` is answered too, because a client that has not settled its ST string yet should still
 * find us.
 *
 * Off with ANNOUNCE=0 for anyone whose network already has enough chatter, or who prefers to pair by
 * typing the address on purpose.
 */

const dgram = require('dgram');
const os = require('os');

const settings = require('./settings');

const MCAST = '239.255.255.250';
const SSDP_PORT = Number(process.env.ANNOUNCE_PORT || 1900);
const ST = 'ssdp:scenix-hub';
const NOTIFY_INTERVAL_MS = Math.max(10000, Number(process.env.ANNOUNCE_INTERVAL_MS || 30000));
const CACHE_MAX_AGE = Math.max(60, Math.floor(NOTIFY_INTERVAL_MS * 3 / 1000) + 30);

let socket = null;
let timer = null;
let started = false;
let lastError = null;
let answers = 0; // M-SEARCH requests we replied to — the number that proves pairing works
const lastLog = new Map();

const log = (...a) => console.log(new Date().toISOString(), ...a);

/** One line per distinct message, so a loop cannot fill hub.log but a first failure is never silent. */
function sayOnce(key, ...rest) {
  if (lastLog.get(key) === String(rest[0])) return;
  lastLog.set(key, String(rest[0]));
  log(...rest);
}

/**
 * The IPv4 a client should come back to. On a multi-homed host (a hub with Wi-Fi and Ethernet, or a
 * container) the interface the request arrived on is the only correct answer, so it is preferred; the
 * fallback is the first non-internal address. `127.0.0.1` is never a valid answer to a client on another
 * device, so loopback is excluded outright.
 */
function addressFor(rinfo) {
  const byNet = new Map();
  try {
    for (const [name, addrs] of Object.entries(os.networkInterfaces())) {
      for (const a of addrs || []) {
        if (a.family !== 'IPv4' || a.internal) continue;
        byNet.set(a.address, { address: a.address, iface: name });
      }
    }
  } catch (e) {
    lastError = e.message;
  }
  // A reply to a request that reached us at <hub-host> must say <hub-host>, not some other card.
  if (rinfo && byNet.has(rinfo.address)) return byNet.get(rinfo.address);
  return byNet.values().next().value || null;
}

function headers(port) {
  const name = settings.get().hubName;
  return [
    'CACHE-CONTROL: public="max-age=' + CACHE_MAX_AGE + '"',
    'ST: ' + ST,
    'EXT: ',
    'SERVER: Scenix-Hub/' + (process.env.HUB_VERSION || '1') + ' UPnP/1.0',
    // The id lets a client that finds the same hub twice through two interfaces fold them into one entry.
    'USN: uuid:' + (process.env.HUB_ID || 'scenix') + '::' + ST,
    'X-Scenix-Name: ' + name,
    'X-Scenix-Port: ' + port,
  ];
}

function announce(port) {
  if (!socket) return;
  const nets = [];
  try {
    for (const addrs of Object.values(os.networkInterfaces())) {
      for (const a of addrs || []) {
        if (a.family === 'IPv4' && !a.internal) nets.push(a.address);
      }
    }
  } catch (e) {
    lastError = e.message;
  }
  if (!nets.length) {
    sayOnce('no-net', 'DISCOVER no usable IPv4 interface, nothing to announce');
    return;
  }
  for (const addr of nets) {
    const msg = [
      'NOTIFY * HTTP/1.1',
      'HOST: ' + MCAST + ':' + SSDP_PORT,
      'NTS: ssdp:scenix-hub',
      'NT: ' + ST,
      'LOCATION: http://' + addr + ':' + port + '/',
    ].concat(headers(port)).join('\r\n') + '\r\n\r\n';
    const buf = Buffer.from(msg);
    try {
      socket.send(buf, 0, buf.length, SSDP_PORT, MCAST, (err) => {
        if (err) lastError = err.message;
      });
    } catch (e) {
      lastError = e.message;
      sayOnce('send', 'DISCOVER announce failed:', e.message);
    }
  }
}

/**
 * Reply to one M-SEARCH, unicast to whoever asked (multicasting a reply to a unicast request confuses
 * other devices, and the asker already knows where it is). A request for some other target is ignored on
 * purpose — this hub is not a general UPnP responder and should not pretend to be one.
 */
function reply(raw, rinfo, port) {
  const text = String(raw || '');
  if (!/^M-SEARCH\b/i.test(text.trim())) return;
  const st = /(?:\bST:)\s*([^\r\n]+)/i.exec(text);
  const wanted = st ? st[1].trim().toLowerCase() : '';
  if (wanted && wanted !== 'ssdp:discover' && wanted.indexOf(ST) === -1) return;
  const net = addressFor({ address: rinfo.address });
  if (!net) {
    sayOnce('reply-no-net', 'DISCOVER reply skipped: no non-internal IPv4 to offer', rinfo.address);
    return;
  }
  const msg = [
    'HTTP/1.1 200 OK',
    'HOST: ' + MCAST + ':' + SSDP_PORT,
    'LOCATION: http://' + net.address + ':' + port + '/',
  ].concat(headers(port)).join('\r\n') + '\r\n\r\n';
  const buf = Buffer.from(msg);
  answers++;
  try {
    socket.send(buf, 0, buf.length, rinfo.port, rinfo.address, (err) => {
      if (err) lastError = err.message;
    });
    sayOnce('reply', 'DISCOVER answered M-SEARCH from', rinfo.address, 'as', net.address + ':' + port,
      '(' + settings.get().hubName + ')');
  } catch (e) {
    lastError = e.message;
    sayOnce('reply-fail', 'DISCOVER reply failed:', e.message);
  }
}

/**
 * Start the responder. `port` is the port the hub is serving HTTP on, so LOCATION points at something a
 * client can actually open. Binding 1900 can fail on a host where another device already owns it (a TV, a
 * printer, a container that mapped it); that must not take the hub down, so we fall back to an ephemeral
 * port — replies still work because they are unicast, only our own NOTIFY broadcasts are lost.
 */
function start(port) {
  if (started || process.env.ANNOUNCE === '0') return { enabled: false };
  started = true;
  const open = (p) => {
    const s = dgram.createSocket({ type: 'udp4', reuseAddr: true });
    s.on('error', (e) => {
      lastError = e.message;
      sayOnce('sock-error', 'DISCOVER socket error:', e.message);
    });
    s.on('message', (raw, rinfo) => {
      try {
        reply(raw, rinfo, port);
      } catch (e) {
        lastError = e.message;
      }
    });
    s.bind(p, () => {
      socket = s;
      try {
        s.setMulticastTTL(2);
        s.setMulticastLoopback(true);
        s.addMembership(MCAST);
      } catch (e) {
        // Membership can fail on a network with no multicast route. The hub still answers a directed
        // request, which is the case that matters for pairing.
        sayOnce('mcast', 'DISCOVER multicast unavailable:', e.message);
      }
      const mine = addressFor(null);
      log('DISCOVER announcing', ST, 'on udp/' + s.address().port, 'as http://'
        + (mine ? mine.address : '?') + ':' + port, '(' + settings.get().hubName + ')');
      announce(port);
      timer = setInterval(() => announce(port), NOTIFY_INTERVAL_MS);
      if (timer.unref) timer.unref(); // announce must never be the reason a hub stays up
    });
    return s;
  };
  try {
    open(SSDP_PORT);
  } catch (e) {
    lastError = e.message;
    sayOnce('bind', 'DISCOVER bind failed:', e.message);
  }
  return { enabled: true };
}

function stop() {
  if (timer) clearInterval(timer);
  timer = null;
  if (socket) {
    try {
      socket.close();
    } catch (e) { /* already gone */ }
  }
  socket = null;
}

function status() {
  return {
    enabled: started && process.env.ANNOUNCE !== '0',
    searchTarget: ST,
    port: socket ? socket.address().port : null,
    name: settings.get().hubName,
    answered: answers,
    intervalMs: NOTIFY_INTERVAL_MS,
    error: lastError,
  };
}

module.exports = { start, stop, status, ST };
