const core = require(process.argv[2] + '/bytecart-core.js').bytecart;
const fs = require('fs');
for (const f of process.argv.slice(3)) {
  const r = core.compileSource(fs.readFileSync(f, 'utf8'));
  console.log(f.split('/').pop(), Buffer.from(Int8Array.from(r.image).buffer).toString('hex'));
}
