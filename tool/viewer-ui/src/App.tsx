import { Navigate, Route, Routes, useMatch } from 'react-router';
import { ErrorBanner } from './ErrorBanner';
import { PrimaryBoard } from './board/PrimaryBoard';
import { SidePanel } from './panel/SidePanel';
import { ScenarioPicker } from './scenarios/ScenarioPicker';
import { SequenceView } from './sequence/SequenceView';
import { useWorkspace } from './api/queries';

/** The shell every page shares: a header with the project name and the page below it. New pages are new routes here. */
export function App() {
  return (
    <div className="app">
      <Header />
      <ErrorBanner />
      <Routes>
        <Route path="/" element={<BoardPage />} />
        <Route path="/sequence/:runId" element={<SequenceView />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </div>
  );
}

function Header() {
  const { data } = useWorkspace();
  const onSequence = useMatch('/sequence/:runId') !== null;
  return (
    <header className="app-header">
      <span className="app-logo" aria-hidden="true" />
      <h1>{data?.project.name ?? 'Stack viewer'}</h1>
      <span className="app-subtitle">{onSequence ? 'Sequence view' : 'Primary board'}</span>
    </header>
  );
}

function BoardPage() {
  return (
    <main className="board-page">
      <div className="board-main">
        <ScenarioPicker />
        <div className="board-canvas">
          <PrimaryBoard />
        </div>
      </div>
      <SidePanel />
    </main>
  );
}
