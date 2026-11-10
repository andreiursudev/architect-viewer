import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, expect, test, vi } from 'vitest';
import { ManageWorkspacesDialog } from './ManageWorkspacesDialog';
import { entry, renderAt, stubWorkspaces } from './testing';

afterEach(() => vi.unstubAllGlobals());

test('renaming the current workspace moves to its new address', async () => {
  const user = userEvent.setup();
  const { list } = stubWorkspaces([entry('sam'), entry('other')], ({ body }, l) => {
    l[0] = entry((body as { name: string }).name);
    return Response.json(l[0]);
  });
  renderAt('/w/sam/sequence/run-1', <ManageWorkspacesDialog onClose={() => {}} />);

  const row = (await screen.findByText('sam')).closest('li') as HTMLElement;
  await user.click(within(row).getByRole('button', { name: 'Rename' }));
  const input = within(row).getByLabelText('New name');
  await user.clear(input);
  await user.type(input, 'sam2');
  await user.click(within(row).getByRole('button', { name: 'Save' }));

  await vi.waitFor(() => expect(screen.getByTestId('where')).toHaveTextContent('/w/sam2/sequence/run-1'));
  expect(list[0].name).toBe('sam2');
});

test('a rejected rename shows the reason', async () => {
  const user = userEvent.setup();
  stubWorkspaces([entry('sam')], () => Response.json({ error: 'name taken' }, { status: 400 }));
  renderAt('/w/sam/', <ManageWorkspacesDialog onClose={() => {}} />);
  await user.click(await screen.findByRole('button', { name: 'Rename' }));
  await user.type(screen.getByLabelText('New name'), 'x');
  await user.click(screen.getByRole('button', { name: 'Save' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('name taken');
});

test('removing the current workspace asks first, deletes on confirm and goes to /', async () => {
  const user = userEvent.setup();
  const { calls } = stubWorkspaces([entry('sam'), entry('other')], (_c, l) => {
    l.shift();
    return new Response(null, { status: 204 });
  });
  renderAt('/w/sam/', <ManageWorkspacesDialog onClose={() => {}} />);

  const row = (await screen.findByText('sam')).closest('li') as HTMLElement;
  await user.click(within(row).getByRole('button', { name: 'Remove' }));
  expect(screen.getByText('Remove sam from the list? No files are deleted.')).toBeInTheDocument();
  expect(calls).toHaveLength(0);
  await user.click(screen.getByRole('button', { name: 'Remove from list' }));

  await vi.waitFor(() => expect(calls).toEqual([{ method: 'DELETE', url: '/api/workspaces/sam', body: undefined }]));
  await vi.waitFor(() => expect(screen.getByTestId('where')).toHaveTextContent(/^\/$/));
});

test('removing another workspace stays where it is; cancel deletes nothing', async () => {
  const user = userEvent.setup();
  const { calls } = stubWorkspaces([entry('sam'), entry('other')], (_c, l) => {
    l.pop();
    return new Response(null, { status: 204 });
  });
  renderAt('/w/sam/', <ManageWorkspacesDialog onClose={() => {}} />);
  const row = (await screen.findByText('other')).closest('li') as HTMLElement;
  await user.click(within(row).getByRole('button', { name: 'Remove' }));
  await user.click(screen.getByRole('button', { name: 'Cancel' }));
  expect(calls).toHaveLength(0);
  await user.click(within(row).getByRole('button', { name: 'Remove' }));
  await user.click(screen.getByRole('button', { name: 'Remove from list' }));
  await vi.waitFor(() => expect(calls).toHaveLength(1));
  expect(screen.getByTestId('where')).toHaveTextContent('/w/sam/');
});
