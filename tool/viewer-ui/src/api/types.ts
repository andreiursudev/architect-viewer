import type { ConnectionKind, ConnectionState, ComponentType, ModelSchema, NodePosition } from '../generated/model';

// The generated schema types are anonymous nested shapes; these aliases give the view models the names the backend uses.
export type { ComponentType, ConnectionKind, ConnectionState, NodePosition };
export type WorkspaceView = ModelSchema['workspaceView'];
export type BoardComponent = WorkspaceView['components'][number];
export type BoardConnection = WorkspaceView['connections'][number];
export type ScenarioSummary = WorkspaceView['scenarios'][number];
export type BoardLayout = ModelSchema['boardLayout'];
export type Viewport = NonNullable<BoardLayout['viewport']>;
export type Flow = ModelSchema['flow'];
export type FlowStep = Flow['steps'][number];
export type RecordingManifest = ModelSchema['recordingManifest'];
export type ComponentDefinition = ModelSchema['componentDefinition'];
export type ConnectionDefinition = ModelSchema['connectionDefinition'];
