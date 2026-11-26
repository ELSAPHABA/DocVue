module.exports = {
  '/api/v1/**': {
    target: `http://127.0.0.1:${process.env.CORE_HOST_PORT || 8080}`,
    secure: false,
  },
  '/realtime-health': {
    target: `http://127.0.0.1:${process.env.REALTIME_HOST_PORT || 8081}`,
    secure: false,
    pathRewrite: { '^/realtime-health': '/actuator/health/readiness' },
  },
};
