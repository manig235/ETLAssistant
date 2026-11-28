param(
  [Parameter(Mandatory=$true)][ValidateSet('docx','xlsx','pptx')][string]$Kind,
  [Parameter(Mandatory=$true)][string]$SourcePath,
  [Parameter(Mandatory=$true)][string]$OutputPath
)
$ErrorActionPreference='Stop'
$application=$null
$document=$null
$owned=$false
$guard=$null
$oldLinks=$null
$oldFields=$null
try {
  if (-not [System.IO.Path]::IsPathRooted($SourcePath) -or -not [System.IO.Path]::IsPathRooted($OutputPath)) { throw 'INVALID_PATH' }
  switch ($Kind) {
    'docx' {
      $application=New-Object -ComObject Word.Application
      $owned=$true
      $application.Visible=$false
      $application.DisplayAlerts=0
      $application.AutomationSecurity=3
      $oldLinks=$application.Options.UpdateLinksAtOpen
      $oldFields=$application.Options.UpdateFieldsAtPrint
      $application.Options.UpdateLinksAtOpen=$false
      $application.Options.UpdateFieldsAtPrint=$false
      $document=$application.Documents.Open($SourcePath,$false,$true,$false)
      $document.ExportAsFixedFormat($OutputPath,17)
    }
    'xlsx' {
      $application=New-Object -ComObject Excel.Application
      $owned=$true
      $application.Visible=$false
      $application.DisplayAlerts=$false
      $application.AskToUpdateLinks=$false
      $application.AutomationSecurity=3
      $guard=$application.Workbooks.Add()
      $application.Calculation=-4135
      $application.CalculateBeforeSave=$false
      $document=$application.Workbooks.Open($SourcePath,0,$true)
      $document.ExportAsFixedFormat(0,$OutputPath)
    }
    'pptx' {
      # PowerPoint can reuse a running application. Leave the user's instance alone.
      if (Get-Process POWERPNT -ErrorAction SilentlyContinue) { throw 'POWERPOINT_IN_USE' }
      $application=New-Object -ComObject PowerPoint.Application
      $owned=$true
      $application.AutomationSecurity=3
      $document=$application.Presentations.Open($SourcePath,-1,0,0)
      $document.SaveAs($OutputPath,32)
    }
  }
  if (-not [System.IO.File]::Exists($OutputPath)) { throw 'NO_OUTPUT' }
  [Console]::Out.WriteLine('OFFICE_RENDER_OK')
} catch {
  [Console]::Out.WriteLine('OFFICE_RENDER_UNAVAILABLE')
  exit 1
} finally {
  if ($document) {
    try { if ($Kind -eq 'docx') {$document.Close(0)} elseif ($Kind -eq 'xlsx') {$document.Close($false)} else {$document.Close()} } catch {}
    try { [void][System.Runtime.InteropServices.Marshal]::FinalReleaseComObject($document) } catch {}
  }
  if ($application -and $owned) {
    try {
      if ($Kind -eq 'docx' -and $null -ne $oldLinks) {$application.Options.UpdateLinksAtOpen=$oldLinks;$application.Options.UpdateFieldsAtPrint=$oldFields}
      if ($guard) {$guard.Close($false);[void][System.Runtime.InteropServices.Marshal]::FinalReleaseComObject($guard)}
      # A user may have opened a deck during our conversion; close only our deck in that case.
      if (($Kind -eq 'pptx' -and $application.Presentations.Count -eq 0) -or ($Kind -eq 'docx' -and $application.Documents.Count -eq 0) -or ($Kind -eq 'xlsx' -and $application.Workbooks.Count -eq 0)) {$application.Quit()}
    } catch {}
    try { [void][System.Runtime.InteropServices.Marshal]::FinalReleaseComObject($application) } catch {}
  }
}
