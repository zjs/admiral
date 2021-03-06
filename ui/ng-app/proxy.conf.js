const ENDPOINTS = [
  '/tenants/*',
  '/authn/*',
  '/mgmt/*',
  '/query/*',
  '/core/*',
  '/provisioning/*',
  '/resources/*',
  '/container-image-icons/*',
  '/projects/*',
  '/config/*',
  '/templates/*',
  '/groups/*',
  '/image-assets/*',
  '/popular-images/*',
  '/hbr-api/*',
  '/auth/*',
  '/index-no-navigation.html*',
  '/lib/*',
  '/js/*',
  '/styles/*',
  '/messages/*',
  '/fonts/*',
  '/util/*'
];

var configure = function(env) {
  var configs = {};

  ENDPOINTS.forEach((e) => {
    configs[e] = {
      'target': `http://${env.services.ip}:${env.services.port}`,
      'secure': false
    };
  });

  configs['/assets/i18n/base*.json'] = {
    'target': `http://${env.services.ip}:${env.services.port}/ng`,
    'secure': false
  }

  return configs;
}

var port = process.env.NG_PORT || '8282';
console.log('proxying to localhost:' + port);

var configs = configure({
  services: {
    ip: 'localhost',
    port: port
  }
});

module.exports = configs;
