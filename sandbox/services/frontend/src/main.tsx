import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { startStackRecording } from '@stackvibed/browser';
import { App } from './App';

startStackRecording({ component: 'frontend' });

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
