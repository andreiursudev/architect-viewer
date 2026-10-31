import { BrowserRouter, Route, Routes } from 'react-router';
import { OrderFormPage } from './OrderFormPage';
import { OrderPage } from './OrderPage';
import { StockPage } from './StockPage';

export function App() {
  return (
    <BrowserRouter>
      <h1>Sandbox shop</h1>
      <Routes>
        <Route path="/" element={<StockPage />} />
        <Route path="/order" element={<OrderFormPage />} />
        <Route path="/orders/:id" element={<OrderPage />} />
      </Routes>
    </BrowserRouter>
  );
}
