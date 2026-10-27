import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach } from 'vitest';
import { useUiStore } from '../store/uiStore';

const initialUiState = useUiStore.getState();

afterEach(() => {
  cleanup();
  useUiStore.setState(initialUiState, true);
});
