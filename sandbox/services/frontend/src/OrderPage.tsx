import { useEffect, useState } from 'react';
import { useParams } from 'react-router';
import { getOrder, type Order } from './api';

export function OrderPage() {
  const { id } = useParams<{ id: string }>();
  const [order, setOrder] = useState<Order>();
  const [error, setError] = useState<string>();

  useEffect(() => {
    getOrder(id!).then(setOrder, (e: Error) => setError(e.message));
  }, [id]);

  return (
    <main>
      <h2>Order</h2>
      {error && <p role="alert">{error}</p>}
      {order && (
        <dl data-testid="order">
          <dt>Id</dt>
          <dd>{order.id}</dd>
          <dt>SKU</dt>
          <dd>{order.sku}</dd>
          <dt>Quantity</dt>
          <dd>{order.quantity}</dd>
          <dt>Status</dt>
          <dd>{order.status}</dd>
        </dl>
      )}
    </main>
  );
}
