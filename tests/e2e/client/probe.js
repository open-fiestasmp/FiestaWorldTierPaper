const fs = require('node:fs')
const mc = require('minecraft-protocol')

const readyPath = process.argv[2]
const resultPath = process.argv[3]
const started = performance.now()
let firstChunk
let targetChunk
let loggedIn = false

function report(line) {
  const output = `${(performance.now() - started).toFixed(1)}ms ${line}`
  console.log(output)
  fs.appendFileSync(resultPath, `${output}\n`)
}

const client = mc.createClient({
  host: process.env.FIESTA_TEST_HOST || '127.0.0.1',
  port: Number(process.env.FIESTA_TEST_PORT || 25671),
  username: process.env.FIESTA_TEST_USERNAME || 'TierLatencyProbe',
  auth: 'offline',
  version: '1.21.11'
})

client.on('packet', (packet, meta) => {
  if (!loggedIn && /login|configuration|finish_configuration/.test(meta.name)) {
    loggedIn = true
    fs.writeFileSync(readyPath, `${Date.now()} ${meta.name}\n`)
    report(`LOGIN_PACKET:${meta.name}`)
  }

  if (/chunk/.test(meta.name)) {
    const coords = `${packet.x},${packet.z}`
    if (!firstChunk) {
      firstChunk = performance.now()
      report(`FIRST_CHUNK_PACKET:${meta.name}:${coords}`)
    }
    if (packet.x === 320 && packet.z === 320 && !targetChunk) {
      targetChunk = performance.now()
      report(`TARGET_CHUNK_PACKET:${meta.name}:${coords}`)
      client.end()
    }
  }
})

client.on('error', error => report(`CLIENT_ERROR:${error.message}`))
client.on('end', reason => report(`CLIENT_END:${String(reason || '')}`))

setTimeout(() => {
  if (!targetChunk) report('CLIENT_TIMEOUT_WAITING_FOR_TARGET_CHUNK')
  client.end()
}, 45_000).unref()
