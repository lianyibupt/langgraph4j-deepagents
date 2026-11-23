#!/usr/bin/env python3
"""Five essential integration flows against a running local workbench.

Run python3 personal-agent/smoke-web.py [http://127.0.0.1:8080].
Uses synthetic notes and protocol replay. Test records stay visible in the UI.
"""
import json,urllib.request,urllib.error,time,pathlib,tempfile,sys
BASE=(sys.argv[1] if len(sys.argv)>1 else 'http://127.0.0.1:8080').rstrip('/')+'/api/'
def api(path, body=None):
    req=urllib.request.Request(BASE+path,data=None if body is None else json.dumps(body).encode(),headers={'Content-Type':'application/json'})
    try:
        with urllib.request.urlopen(req,timeout=10) as response:
            raw=response.read()
            return json.loads(raw) if raw else None
    except urllib.error.HTTPError as e:
        if e.code == 404 and path.startswith('runs/'): return {'run': {'status': 'PENDING'}}
        raise AssertionError((path,e.code,e.read().decode())) from e
def wait(read,predicate,label):
    for _ in range(150):
        data=read()
        if predicate(data): return data
        time.sleep(.1)
    raise AssertionError((label,data))
def run_done(id, status='COMPLETED'):
    return wait(lambda:api('runs/'+id),lambda x:x['run']['status']==status,'run '+id+' '+status)
def event_for(watch):
    return wait(lambda:api('management'),lambda state:any(auto_trigger['watchId']==watch for auto_trigger in state['triggers']),'source event')
input_dir=pathlib.Path(tempfile.mkdtemp(prefix='agent-courses-'))
(input_dir/'Agent.md').write_text('# Agent\n\n先理解工具调用和上下文。\n')
test_state={'fixture':str(input_dir)}
api('delivery',{'enabled':False})
base={'title':'课程验收 · 持续研究','inputPath':str(input_dir),'preset':'notes','provider':'replay','goal':'总结 Agent 资料变化并引用原文。','keywords':'Agent','mode':'auto','intervalSeconds':86400,'dailyLimit':1}
auto_watch=api('watches',base);test_state['watch']=auto_watch['id']
(input_dir/'Agent.md').write_text('# Agent\n\n工具结果和上下文的新变化。\n')
api('watches/'+auto_watch['id']+'/scan',{})
state=event_for(auto_watch['id']);auto_trigger=next(x for x in state['triggers'] if x['watchId']==auto_watch['id'])
run=run_done(auto_trigger['id']);test_state['run']=auto_trigger['id']
api('watches/'+auto_watch['id']+'/scan',{});time.sleep(1.3)
state=api('management')
assert len([x for x in state['triggers'] if x['watchId']==auto_watch['id']])==1
assert run['note'] and run['modelInput'] and len(run['messages'])>1
assert pathlib.Path(auto_trigger['snapshot'],'Agent.md').read_text().startswith('# Agent')
print('PASS automatic change intake, immutable snapshot, research, and duplicate scan',flush=True)
(input_dir/'Agent.md').write_text('# Agent\n\n第三个版本用于检查每日预算。\n')
api('watches/'+auto_watch['id']+'/scan',{})
state=wait(lambda:api('management'),lambda state:any(auto_trigger['watchId']==auto_watch['id'] and auto_trigger['status']=='WAITING_BUDGET' for auto_trigger in state['triggers']),'daily budget')
assert next(auto_trigger for auto_trigger in state['triggers'] if auto_trigger['watchId']==auto_watch['id'] and auto_trigger['status']=='WAITING_BUDGET')['runId'] is None
api('watches/'+auto_watch['id']+'/stop',{})
base.update(title='课程验收 · 观察后批准',mode='observe')
observed_watch=api('watches',base);test_state['observe']=observed_watch['id']
(input_dir/'Agent.md').write_text('# Agent\n\n第二次变化，需要检查点和恢复。\n')
api('watches/'+observed_watch['id']+'/scan',{})
state=event_for(observed_watch['id']);observed_trigger=next(x for x in state['triggers'] if x['watchId']==observed_watch['id'])
assert observed_trigger['status']=='OBSERVED' and observed_trigger['runId'] is None
api('triggers/'+observed_trigger['id']+'/approve',{});run_done(observed_trigger['id'])
print('PASS observation records a decision and single approval starts research',flush=True)
question_request={'preset':'notes','provider':'replay','question':'[教学：先询问] Agent 研究，请先确认方向。','inputPath':str(input_dir),'maxTurns':10,'pause':False}
question_run=api('runs',question_request)['run']['id'];run_done(question_run,'WAITING_INPUT')
api('runs/'+question_run+'/input',{'text':'优先说明工具结果如何进入上下文。'})
answered=run_done(question_run)
assert answered['inbox'][0]['appliedAt'] and any('优先说明' in m['text'] for m in answered['modelInput'])
cancelled_run=api('runs',question_request)['run']['id'];run_done(cancelled_run,'WAITING_INPUT')
api('runs/'+cancelled_run+'/actions/stop',{})
assert api('runs/'+cancelled_run)['run']['status']=='CANCELLED'
print('PASS ask-user yields the worker, durable answer is applied, waiting task can stop',flush=True)
state=wait(lambda:api('management'),lambda state:any(notice['runId']==observed_trigger['id'] for notice in state['notices']),'pending outbox')
notice=next(notice for notice in state['notices'] if notice['runId']==observed_trigger['id'])
assert notice['status']=='PENDING'
before=api('runs/'+observed_trigger['id'])['run']['modelTurns']
api('delivery',{'enabled':True})
notice_id=notice['id']
state=wait(lambda:api('management'),lambda state:any(notice['id']==notice_id and notice['status']=='SENT' for notice in state['notices']),'delivery')
assert api('runs/'+observed_trigger['id'])['run']['modelTurns']==before
assert pathlib.Path(api('config')['projectPath'],'out/web/notifications',notice_id+'.md').exists()
print('PASS outbox resumes delivery without repeating research',flush=True)
api('watches/'+observed_watch['id']+'/pause',{})
goal=api('goals',{'title':'课程验收 · Java 学习偏好','content':'解释 Agent 时展示 Java 调用链和可断点观察的状态。','keywords':'Agent','inputPath':str(input_dir),'preset':'notes','provider':'replay','mode':'observe'})
question_request['question']='整理 Agent 的执行机制。'
goal_run=api('runs',question_request)['run']['id'];d=run_done(goal_run)
assert goal['id'] in d['background'] and any('Java 调用链' in m['text'] for m in d['modelInput'])
state=api('management');suggestion=next(x for x in state['suggestions'] if x['goalId']==goal['id'])
assert suggestion['status']=='PROPOSED'
api('suggestions/'+suggestion['id'],{'enabled':True})
state=api('management');created=next(x for x in state['watches'] if x['goalId']==goal['id'])
api('goals/'+goal['id'],{'enabled':False})
assert next(x for x in api('management')['watches'] if x['id']==created['id'])['status']=='PAUSED'
test_state['goal']=goal['id'];test_state['notice']=notice_id
pathlib.Path('/tmp/stages8-12-smoke-state.json').write_text(json.dumps(test_state))
api('watches/'+observed_watch['id']+'/stop',{})
api('watches/'+created['id']+'/stop',{})
print('PASS goal recall includes provenance, suggestion is approved, disabling goal pauses its watch',flush=True)
print(json.dumps(test_state),flush=True)
