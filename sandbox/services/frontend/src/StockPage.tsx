import { useEffect, useState } from 'react';
import { Link } from 'react-router';
import { getStock, type Stock } from './api';

export function StockPage() {
  const [stock, setStock] = useState<Stock[]>();
  const [error, setError] = useState<string>();

  useEffect(() => {
    getStock().then(setStock, (e: Error) => setError(e.message));
  }, []);

  return (
    <main>
      <h2>Stock</h2>
      {error && <p role="alert">{error}</p>}
      {stock && (
        <table>
          <thead>
            <tr>
              <th>SKU</th>
              <th>Available</th>
            </tr>
          </thead>
          <tbody>
            {stock.map((item) => (
              <tr key={item.sku}>
                <td>{item.sku}</td>
                <td>{item.available}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <p>
        <Link to="/order" data-testid="to-order">
          Place an order
        </Link>
      </p>
    </main>
  );
}
