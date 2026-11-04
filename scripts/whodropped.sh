#!/bin/zsh
# Which rule dropped this title. On the hub, with the same rules the play route runs.
IDENT=${1:-imdb:tt0468944}
PROFILE=${2:-1001}
ssh -o BatchMode=yes nagraj@192.168.0.24 "cd ~/tvhub && node -e \"
const store=require('./core/store').create(process.cwd());
const settings=require('./core/settings');
const registry=require('./core/registry').load(process.cwd());
const prof = store.profile('$PROFILE') || null;
console.log('profile:', JSON.stringify(prof && {id:prof.id,kind:prof.kind,age:prof.age,languages:prof.languages,rules:prof.rules}));
console.log('hub languages:', JSON.stringify(settings.languages()));
const it={identity:'$IDENT',type:'movie',title:'The Dark Knight',year:'2008'};
const ctx={ profile: prof || {id:'x'}, prefs:{xxxEnabled:false}, store };
(async () => {
  let x=it;
  for (const rule of registry.rules) {
    try { x = await rule.apply(x, ctx); } catch(e) { console.log('rule', rule.id, 'ERROR', e.message); continue; }
    console.log('after', rule.id, x ? ('kept (tier ' + x.tier + ' minAge ' + x.minAge + ' langs ' + JSON.stringify(x.languages) + ')') : 'DROPPED');
    if (!x) break;
  }
})();
\" 2>&1 | head -12"
