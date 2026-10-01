// @vitest-environment jsdom
import {render,screen} from '@testing-library/react';
import {describe,it,expect} from 'vitest';
import type {ChatAgentRun} from '@plot/api-client';
import {AgentActivityDetail} from './chat-activity';
import {ChatSourceCitations} from './chat-source-citations';
const run:ChatAgentRun={id:'run',chatId:'chat',status:'QUEUED',failureCode:null,responseText:null,artifactId:null,artifact:null,instruction:'Release',createdAt:'2026-07-01T00:00:00Z',updatedAt:'2026-07-01T00:00:00Z'};
describe('AI presentation',()=>{
 it('maps queued, running, completed artifact and failed status without changing the run',()=>{
  const {container,rerender}=render(<AgentActivityDetail run={run} busy error="" instruction="Release" references={[]}/>);
  expect(container.querySelector('[data-tool-state]')).toHaveAttribute('data-tool-state','input-streaming');
  rerender(<AgentActivityDetail run={{...run,status:'RUNNING'}} busy error="" instruction="Release" references={[]}/>);
  expect(container.querySelector('[data-tool-state]')).toHaveAttribute('data-tool-state','input-available');
  rerender(<AgentActivityDetail run={{...run,status:'SUCCEEDED',artifactId:'artifact'}} busy={false} error="" instruction="Release" references={[]}/>);
  expect(container.querySelector('[data-tool-state]')).toHaveAttribute('data-tool-state','output-available');
  rerender(<AgentActivityDetail run={{...run,status:'FAILED'}} busy={false} error="" instruction="Release" references={[]}/>);
  expect(container.querySelector('[data-tool-state]')).toHaveAttribute('data-tool-state','output-error');
  expect(screen.getByRole('alert')).toHaveTextContent('could not complete');
 });
 it('keeps source labels and counts while rejecting unsafe URLs',()=>{
  const {container,rerender}=render(<ChatSourceCitations sources={[]}/>);
  expect(container).toBeEmptyDOMElement();
  rerender(<ChatSourceCitations totalCount={4} sources={[{id:'safe',title:'Safe',url:'https://example.com'},{id:'bad',title:'Untrusted',url:'javascript:alert(1)'},{id:'empty',title:'No URL'}]}/>);
  expect(screen.getByRole('link',{name:'Citation 1: Safe'})).toHaveAttribute('rel','noopener noreferrer');
  expect(container.querySelectorAll('a')).toHaveLength(1);
  expect(screen.getByText('Untrusted')).toBeVisible();expect(screen.getByText('No URL')).toBeVisible();expect(screen.getByText('1 more')).toBeVisible();
 });
});
