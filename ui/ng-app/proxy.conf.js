
let env = {
  services: {
    ip: 'localhost',
    port: '8282'
  }
}

const ENDPOINTS = [
  '/tenants/*',
  '/authn/*',
  '/mgmt/*',
  '/query/*',
  '/core/*',
  '/provisioning/*',
  '/resources/*',
  '/container-image-icons/*',
  '/messages/*',
  '/projects/*'
];

var configs = {};

ENDPOINTS.forEach((e) => {
  configs[e] = {
    'target': `http://${env.services.ip}:${env.services.port}`,
    'secure': false
  };
});

module.exports = configs;
