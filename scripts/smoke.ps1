param(
 [string]$BaseUrl='http://127.0.0.1:18081',
 [string]$DemoPassword='local-demo-change-me'
)
$ErrorActionPreference='Stop'
function Sign-In([string]$Username){
 $body=@{username=$Username;password=$DemoPassword}|ConvertTo-Json -Compress
 (Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/auth/login" -ContentType 'application/json' -Body $body).access_token
}
function Json-Post([string]$Path,[object]$Body,[string]$Token){
 Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1$Path" -Headers @{Authorization="Bearer $Token"} -ContentType 'application/json;charset=utf-8' -Body ([System.Text.Encoding]::UTF8.GetBytes(($Body|ConvertTo-Json -Depth 10 -Compress)))
}
function Await-Task([object]$Task,[string]$Token){
 for($attempt=0;$attempt -lt 60;$attempt++){
  $current=Invoke-RestMethod -Uri "$BaseUrl/api/v1/tasks/$($Task.id)" -Headers @{Authorization="Bearer $Token"}
  if($current.status -in @('completed','awaiting_approval','approved','rejected')){return $current}
  if($current.status -in @('failed','cancelled')){throw "Task ended: $($current.status)"}
  Start-Sleep -Milliseconds 500
 }
 throw 'Task did not complete within 30 seconds'
}
$taskTag=[Guid]::NewGuid().ToString('N')
$sampleRoot=Join-Path ([System.IO.Path]::GetTempPath()) "membrane-java-smoke-$taskTag"
New-Item -ItemType Directory -Path $sampleRoot|Out-Null
$designerToken=Sign-In 'designer'
$reviewerToken=Sign-In 'reviewer'
$documentIds=@()
foreach($kind in @('design','spec')){
 $value=if($kind -eq 'design'){'3500'}else{'4000'}
 $path=Join-Path $sampleRoot "$kind.txt"
 $body=@("SYNTHETIC test only $taskTag",'MODEL-T1','厚度：1.0 mm',"抗拉强度：$value N/5cm") -join [Environment]::NewLine
 [System.IO.File]::WriteAllText($path,$body,[System.Text.UTF8Encoding]::new($false))
 $document=Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/documents/upload" -Headers @{Authorization="Bearer $designerToken"} -Form @{
  project_id='demo-project';version="smoke-$taskTag";doc_type=$kind;material_model='MODEL-T1';classification='internal';file=Get-Item -LiteralPath $path
 }
 $documentIds+= $document.id
 }
$batch=Json-Post '/ingest-batches' @{project_id='demo-project';document_ids=$documentIds;request_id="batch-$taskTag"} $designerToken
foreach($item in $batch.items){
 $null=Await-Task @{id=$item.task_id} $designerToken
}
$progress=Invoke-RestMethod -Uri "$BaseUrl/api/v1/ingest-batches/$($batch.id)" -Headers @{Authorization="Bearer $designerToken"}
if($progress.status -ne 'completed' -or $progress.total -ne 2){throw 'Batch did not complete exactly two files'}
$page=Invoke-RestMethod -Uri "$BaseUrl/api/v1/documents/page?project_id=demo-project&material_model=MODEL-T1&version=smoke-$taskTag&size=20" -Headers @{Authorization="Bearer $designerToken"}
if($page.total -ne 2){throw 'Filtered pagination returned an incorrect total'}
$specUri="$BaseUrl/api/v1/specifications?project_id=demo-project&material_model=MODEL-T1&version=smoke-$taskTag"
$specFirst=Invoke-RestMethod -Uri $specUri -Headers @{Authorization="Bearer $designerToken"}
$specSecond=Invoke-RestMethod -Uri $specUri -Headers @{Authorization="Bearer $designerToken"}
if($specSecond.results.Count -ne 1){throw 'Expected one version-scoped specification'}
if($specFirst.cache -eq 'miss' -and $specSecond.cache -ne 'hit'){throw 'Specification cache was not reused'}
$query=[Uri]::EscapeDataString('抗拉强度')
$found=Invoke-RestMethod -Uri "$BaseUrl/api/v1/documents/search?project_id=demo-project&query=$query&material_model=MODEL-T1&version=smoke-$taskTag" -Headers @{Authorization="Bearer $designerToken"}
$foundIds=@($found.results | ForEach-Object { $_.document_id } | Sort-Object -Unique)
foreach($documentId in $documentIds){
 if($documentId -notin $foundIds){throw 'Indexed source missing from scoped retrieval'}
}
$task=Json-Post '/package-reviews' @{project_id='demo-project';document_ids=$documentIds;request_id="smoke-$taskTag"} $designerToken
$task=Await-Task $task $designerToken
$review=Invoke-RestMethod -Uri "$BaseUrl/api/v1/package-reviews/$($task.target_id)" -Headers @{Authorization="Bearer $reviewerToken"}
if($review.issues.Count -ne 1){throw 'Expected one synthetic tensile-strength finding'}
foreach($issue in $review.issues){
 $review=Invoke-RestMethod -Method Patch -Uri "$BaseUrl/api/v1/package-reviews/$($review.id)/issues/$($issue.id)" -Headers @{Authorization="Bearer $reviewerToken"} -ContentType 'application/json' -Body (@{expected_lock_version=$review.lock_version;reason='SYNTHETIC 人工复核演示，无工程效力'}|ConvertTo-Json -Compress)
}
$receipt=Json-Post "/package-reviews/$($review.id)/decision" @{command_id="approve-$taskTag";expected_lock_version=$review.lock_version;content_hash=$review.content_hash;decision='approved';reason='SYNTHETIC local smoke only'} $reviewerToken
$wordPath=Join-Path $sampleRoot 'review.docx'
Invoke-WebRequest -Uri "$BaseUrl/api/v1/package-reviews/$($review.id)/export?format=docx" -Headers @{Authorization="Bearer $reviewerToken"} -OutFile $wordPath
if((Get-Item -LiteralPath $wordPath).Length -lt 1000){throw 'Word export is unexpectedly small'}
[pscustomobject]@{status=$receipt.status;batch_id=$batch.id;batch_status=$progress.status;execution=$task.execution_mode;cache=$specSecond.cache;review_id=$review.id;word=$wordPath}
