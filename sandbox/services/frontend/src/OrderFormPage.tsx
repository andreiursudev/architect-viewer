import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router';
import { createOrder } from './api';

export function OrderFormPage() {
  const navigate = useNavigate();
  const [customerId, setCustomerId] = useState('c-1');
  const [sku, setSku] = useState('sku-1');
  const [quantity, setQuantity] = useState('1');
  const [outOfStock, setOutOfStock] = useState(false);
  const [error, setError] = useState<string>();

  async function submit(event: FormEvent) {
    event.preventDefault();
    setOutOfStock(false);
    setError(undefined);
    try {
      const result = await createOrder({ customerId, sku, quantity: Number(quantity) });
      if (result.outOfStock) setOutOfStock(true);
      else navigate('/orders/' + result.order.id);
    } catch (e) {
      setError((e as Error).message);
    }
  }

  return (
    <main>
      <h2>Place an order</h2>
      <form data-testid="order-form" onSubmit={submit}>
        <p>
          <label>
            Customer <input name="customerId" value={customerId} onChange={(e) => setCustomerId(e.target.value)} required />
          </label>
        </p>
        <p>
          <label>
            SKU <input name="sku" value={sku} onChange={(e) => setSku(e.target.value)} required />
          </label>
        </p>
        <p>
          <label>
            Quantity{' '}
            <input name="quantity" type="number" min="1" value={quantity} onChange={(e) => setQuantity(e.target.value)} required />
          </label>
        </p>
        <button data-testid="place-order" type="submit">Place order</button>
      </form>
      {outOfStock && <p role="alert">Out of stock</p>}
      {error && <p role="alert">{error}</p>}
    </main>
  );
}
