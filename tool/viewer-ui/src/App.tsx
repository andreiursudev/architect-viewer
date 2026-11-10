import { Navigate, Route, Routes, useMatch } from 'react-router';
import { ErrorBanner } from './ErrorBanner';
import { PrimaryBoard } from './board/PrimaryBoard';
import { SidePanel } from './panel/SidePanel';
import { ScenarioPicker } from './scenarios/ScenarioPicker';
import { SequenceView } from './sequence/SequenceView';
import { Home } from './workspaces/Home';
import { WorkspaceScope } from './workspaces/WorkspaceScope';
import { WorkspaceSwitcher } from './workspaces/WorkspaceSwitcher';
import { useWorkspaceName } from './workspaces/useWorkspaceName';

/**
 * The shell every page shares: a header with the project name and the page below it. New pages are new routes here; a
 * workspace's pages sit under `/w/:ws`.
 */
export function App() {
  return (
    <div className="app">
      <Routes>
        <Route path="/" element={<Home />} />
        <Route path="/w/:ws/*" element={<WorkspacePages />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </div>
  );
}

function WorkspacePages() {
  const ws = useWorkspaceName();
  return (
    <WorkspaceScope>
      <Header />
      <ErrorBanner />
      <Routes>
        <Route path="" element={<BoardPage />} />
        <Route path="sequence/:runId" element={<SequenceView />} />
        <Route path="*" element={<Navigate to={`/w/${encodeURIComponent(ws)}/`} replace />} />
      </Routes>
    </WorkspaceScope>
  );
}

function Header() {
  const onSequence = useMatch('/w/:ws/sequence/:runId') !== null;
  return (
    <header className="app-header">
      <span className="app-logo" aria-hidden="true" />
      <WorkspaceSwitcher />
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
