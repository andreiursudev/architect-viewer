import {
  Background,
  Controls,
  Panel,
  ReactFlow,
  ReactFlowProvider,
  useEdgesState,
  useNodesState,
  useReactFlow,
  type Viewport as FlowViewport,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useFlow, useLayout, useSaveLayout, useWorkspace } from '../api/queries';
import type { BoardLayout } from '../api/types';
import { useUiStore } from '../store/uiStore';
import { autoLayout } from './autoLayout';
import { ComponentNode } from './ComponentNode';
import { ConnectionEdge } from './ConnectionEdge';
import { emphasizePath, HighlightContext, highlightFor, hopsOffBoard } from './highlight';
import { toFlowElements, type ComponentNode as ComponentNodeType, type ConnectionEdgeType } from './toFlowElements';

const BOARD = 'primary';
const SAVE_DELAY_MS = 500;

const nodeTypes = { component: ComponentNode };
const edgeTypes = { connection: ConnectionEdge };

export function PrimaryBoard() {
  return (
    <ReactFlowProvider>
      <Board />
    </ReactFlowProvider>
  );
}

function Board() {
  const workspace = useWorkspace();
  const layoutQuery = useLayout(BOARD);
  const saveLayout = useSaveLayout(BOARD);
  const flow = useReactFlow<ComponentNodeType, ConnectionEdgeType>();
  const selectComponent = useUiStore((s) => s.selectComponent);
  const selectConnection = useUiStore((s) => s.selectConnection);
  const runId = useUiStore((s) => s.selectedRunId);
  const runFlow = useFlow(runId);

  const [nodes, setNodes, onNodesChange] = useNodesState<ComponentNodeType>([]);
  const [edges, setEdges, onEdgesChange] = useEdgesState<ConnectionEdgeType>([]);
  const [ready, setReady] = useState(false);

  const view = workspace.data;

  // The path of the selected run; nothing is highlighted while its flow is loading or when it failed to load.
  const highlight = useMemo(() => (runId && runFlow.data ? highlightFor(runFlow.data) : null), [runId, runFlow.data]);
  const shownEdges = useMemo(() => emphasizePath(edges, highlight), [edges, highlight]);
  const hiddenHops = useMemo(() => hopsOffBoard(highlight, edges.map((e) => e.id)), [edges, highlight]);
  const layoutLoaded = !layoutQuery.isPending;
  const layout = layoutQuery.data ?? null;

  // Keep the saved positions and the latest mutate function reachable from the debounced writer.
  const mutateRef = useRef(saveLayout.mutate);
  mutateRef.current = saveLayout.mutate;
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);

  // [fresh] is the first arrangement, saved before the canvas exists: it has no viewport yet, so the next visit fits the view.
  const persist = useCallback(
    (fresh?: { nodes: BoardLayout['nodes']; viewport: BoardLayout['viewport'] }) => {
      clearTimeout(timer.current);
      timer.current = setTimeout(() => {
        mutateRef.current(
          fresh ?? {
            nodes: Object.fromEntries(flow.getNodes().map((n) => [n.id, { x: n.position.x, y: n.position.y }])),
            viewport: flow.getViewport(),
          },
        );
      }, SAVE_DELAY_MS);
    },
    [flow],
  );
  useEffect(() => () => clearTimeout(timer.current), []);

  // (Re)build the elements whenever the workspace changes; positions the user has already moved are kept.
  useEffect(() => {
    if (!view || !layoutLoaded) return;
    let cancelled = false;
    const elements = toFlowElements(view, layout);
    setEdges(elements.edges);

    const apply = (placed: ComponentNodeType[]) =>
      setNodes((previous) =>
        placed.map((n) => {
          const old = previous.find((p) => p.id === n.id);
          return old ? { ...old, data: n.data } : n;
        }),
      );

    if (elements.unplaced.length === 0) {
      apply(elements.nodes);
      setReady(true);
      return;
    }

    autoLayout(elements.nodes, elements.edges, layout).then((positions) => {
      if (cancelled) return;
      const placed = elements.nodes.map((n) => (positions[n.id] ? { ...n, position: positions[n.id] } : n));
      apply(placed);
      setReady(true);
      // remember the new arrangement so it does not shift when more components appear later
      persist({
        nodes: Object.fromEntries(placed.map((n) => [n.id, { x: n.position.x, y: n.position.y }])),
        viewport: layout?.viewport ?? null,
      });
    });
    return () => {
      cancelled = true;
    };
  }, [view, layout, layoutLoaded, setEdges, setNodes, persist]);

  if (workspace.isError) return <p className="error board-message">Could not load the workspace: {workspace.error.message}</p>;
  if (layoutQuery.isError) return <p className="error board-message">Could not load the layout: {layoutQuery.error.message}</p>;
  if (!view || !layoutLoaded || !ready) return <p className="muted board-message">Loading board…</p>;

  const savedViewport: FlowViewport | undefined = layout?.viewport ?? undefined;

  return (
    <HighlightContext.Provider value={highlight}>
      <ReactFlow<ComponentNodeType, ConnectionEdgeType>
        nodes={nodes}
        edges={shownEdges}
        nodeTypes={nodeTypes}
        edgeTypes={edgeTypes}
        onNodesChange={onNodesChange}
        onEdgesChange={onEdgesChange}
        onNodeClick={(_, node) => selectComponent(node.id)}
        onEdgeClick={(_, edge) => selectConnection(edge.id)}
        onPaneClick={() => selectComponent(null)}
        onNodeDragStart={(_, node) => selectComponent(node.id)}
        onNodeDragStop={() => persist()}
        onMoveEnd={(event) => event && persist()} // a null event is a programmatic move such as fitView
        defaultViewport={savedViewport}
        fitView={!savedViewport}
        fitViewOptions={{ padding: 0.25, maxZoom: 1.1 }}
        minZoom={0.2}
        maxZoom={2}
        nodesConnectable={false}
        deleteKeyCode={null}
        proOptions={{ hideAttribution: true }}
      >
        <Background gap={20} size={1.2} />
        <Controls showInteractive={false} />
        <Panel position="bottom-center" className="legend">
          <span className="legend-item"><i className="legend-line" /> verified</span>
          <span className="legend-item"><i className="legend-line is-dashed" /> unverified</span>
          <span className="legend-item"><i className="legend-line is-discovered" /> discovered</span>
        </Panel>
        {hiddenHops > 0 && (
          <Panel position="top-center" className="path-note">
            {hiddenHops === 1 ? '1 hop of this run is' : `${hiddenHops} hops of this run are`} not on the board (hidden connection or unknown component).
          </Panel>
        )}
      </ReactFlow>
    </HighlightContext.Provider>
  );
}
