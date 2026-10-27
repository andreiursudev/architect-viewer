import { Navigate, Route, Routes } from 'react-router';
import { ErrorBanner } from './ErrorBanner';
import { PrimaryBoard } from './board/PrimaryBoard';
import { SidePanel } from './panel/SidePanel';
import { useWorkspace } from './api/queries';

/** The shell every page shares: a header with the project name and the page below it. New pages are new routes here. */
export function App() {
  return (
    <div className="app">
      <Header />
      <ErrorBanner />
      <Routes>
        <Route path="/" element={<BoardPage />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </div>
  );
}

function Header() {
  const { data } = useWorkspace();
  return (
    <header className="app-header">
      <span className="app-logo" aria-hidden="true" />
      <h1>{data?.project.name ?? 'Stack viewer'}</h1>
      <span className="app-subtitle">Primary board</span>
    </header>
  );
}

function BoardPage() {
  return (
    <main className="board-page">
      <div className="board-canvas">
        <PrimaryBoard />
      </div>
      <SidePanel />
    </main>
  );
}
