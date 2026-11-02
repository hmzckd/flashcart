import http from 'k6/http';
import { check } from 'k6';

const baseUrl = __ENV.BASE_URL || 'http://host.docker.internal:8080';

export const options = {
  vus: 100,
  duration: '30s',
  thresholds: {
    checks: ['rate==1'],
  },
};

export default function () {
  const response = http.get(`${baseUrl}/api/products`);

  check(response, {
    'status is 200': (r) => r.status === 200,
  });
}
