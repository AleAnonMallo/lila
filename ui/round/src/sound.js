var throttle = require('./util').throttle;

function throttled(sound) {
  return throttle(200, false, $.sound[sound]);
}

module.exports = {
  move: throttled('move'),
  capture: throttled('capture'),
  check: throttled('check'),
  explode: throttled('explode')
}
