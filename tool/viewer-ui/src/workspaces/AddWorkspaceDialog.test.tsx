import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, expect, test, vi } from 'vitest';
import { AddWorkspaceDialog } from './AddWorkspaceDialog';
import { entry, renderAt, stubWorkspaces } from './testing';

afterEach(() => vi.unstubAllGlobals());

test('posts the folder alone when the optional fields are empty, then opens the new workspace', async () => {
  const user = userEvent.setup();
  const { calls } = stubWorkspaces([entry('sam')], () => Response.json(entry('fresh'), { status: 201 }));
  const onClose = vi.fn();
  renderAt('/w/sam/', <AddWorkspaceDialog onClose={onClose} />);

  await user.type(screen.getByLabelText('Folder'), 'C:/projects/fresh');
  await user.click(screen.getByRole('button', { name: 'Add' }));

  await vi.waitFor(() => expect(screen.getByTestId('where')).toHaveTextContent('/w/fresh/'));
  expect(calls).toEqual([
    { method: 'POST', url: '/api/workspaces', body: { folder: 'C:/projects/fresh', name: null, recordings: null } },
  ]);
  expect(onClose).toHaveBeenCalled();
});

test('sends the name and recordings folder when given', async () => {
  const user = userEvent.setup();
  const { calls } = stubWorkspaces([], () => Response.json(entry('x'), { status: 201 }));
  renderAt('/', <AddWorkspaceDialog onClose={() => {}} />);
  await user.type(screen.getByLabelText('Folder'), '/f');
  await user.type(screen.getByLabelText('Name'), 'x');
  await user.type(screen.getByLabelText('Recordings folder'), '/r');
  await user.click(screen.getByRole('button', { name: 'Add' }));
  await vi.waitFor(() => expect(calls).toHaveLength(1));
  expect(calls[0].body).toEqual({ folder: '/f', name: 'x', recordings: '/r' });
});

test('a 400 shows its reason and keeps the dialog open', async () => {
  const user = userEvent.setup();
  stubWorkspaces([entry('sam')], () => Response.json({ error: 'not a workspace folder' }, { status: 400 }));
  const onClose = vi.fn();
  renderAt('/w/sam/', <AddWorkspaceDialog onClose={onClose} />);
  await user.type(screen.getByLabelText('Folder'), '/nope');
  await user.click(screen.getByRole('button', { name: 'Add' }));

  const alert = await screen.findByRole('alert');
  expect(alert).toHaveTextContent('not a workspace folder');
  expect(alert).not.toHaveTextContent('failed');
  expect(onClose).not.toHaveBeenCalled();
  expect(screen.getByTestId('where')).toHaveTextContent('/w/sam/');
});
