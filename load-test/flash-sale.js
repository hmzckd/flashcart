import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const baseUrl = __ENV.BASE_URL || 'http://host.docker.internal:8080';
const productId = Number(__ENV.PRODUCT_ID);
const stock = Number(__ENV.STOCK || 100);

const placed = new Counter('orders_placed');
const insufficientStock = new Counter('orders_insufficient_stock');
const tryAgain = new Counter('orders_try_again');

export const options = {
  scenarios: {
    flashSale: {
      executor: 'shared-iterations',
      vus: 200,
      iterations: 5000,
      maxDuration: '5m',
    },
  },
  thresholds: {
    orders_placed: [`count==${stock}`],
    checks: ['rate==1'],
  },
};

export function setup() {
  if (!productId) {
    throw new Error('Set PRODUCT_ID to the id of the product to sell');
  }
}

export default function () {
  const response = http.post(
    `${baseUrl}/api/orders`,
    JSON.stringify({ productId, quantity: 1 }),
    {
      headers: { 'Content-Type': 'application/json' },
      responseCallback: http.expectedStatuses(201, 409),
    },
  );

  check(response, {
    'placed or rejected with 409': (r) => r.status === 201 || r.status === 409,
  });

  if (response.status === 201) {
    placed.add(1);
  } else if (response.status === 409 && response.body.includes('Not enough stock')) {
    insufficientStock.add(1);
  } else if (response.status === 409) {
    tryAgain.add(1);
  }
}
