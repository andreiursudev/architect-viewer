// Same-origin paths: nginx (or the Vite dev server) proxies them to inventory-service and orders-service.

export interface Stock {
  sku: string;
  available: number;
}

export interface NewOrder {
  customerId: string;
  sku: string;
  quantity: number;
}

export interface Order extends NewOrder {
  id: string;
  status: string;
  createdAt: string;
}

/** The result of placing an order: the stored order, or a refusal because the stock is short. */
export type PlaceOrderResult = { outOfStock: false; order: Order } | { outOfStock: true };

export async function getStock(): Promise<Stock[]> {
  const response = await fetch('/stock', { headers: { Accept: 'application/json' } });
  if (!response.ok) throw new Error(`GET /stock answered ${response.status}`);
  return response.json();
}

export async function createOrder(newOrder: NewOrder): Promise<PlaceOrderResult> {
  const response = await fetch('/orders', {
    method: 'POST',
    headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
    body: JSON.stringify(newOrder),
  });
  if (response.status === 409) return { outOfStock: true };
  if (!response.ok) throw new Error(`POST /orders answered ${response.status}`);
  return { outOfStock: false, order: await response.json() };
}

export async function getOrder(id: string): Promise<Order> {
  const response = await fetch(`/orders/${encodeURIComponent(id)}`, { headers: { Accept: 'application/json' } });
  if (!response.ok) throw new Error(`GET /orders/${id} answered ${response.status}`);
  return response.json();
}
