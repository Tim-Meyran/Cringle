🔔 *New Issue Opened*

*Issue #{{issue.number}}*: {{issue.title}}
*Repository*: {{repository.full_name}}
*Opened by*: {{issue.user.login}}
*Type*: {{issue.labels | join", "}}
*Priority*: {{issue.priority | default("unknown")}}

📋 *Issue Details*
```
{{issue.body | truncate(500)}}
```

🔗 *Quick Actions*
- [View Issue]({{issue.html_url}})
- [Add Label]({{issue.html_url}}/labels)
- [Assign]({{issue.html_url}}/assignees/new)
- [Comment]({{issue.html_url}}#new-comment)

📊 *Statistics*
- Total open issues: {{repository.open_issues_count}}
- Issues opened this month: {{repository.recent_issues}}
- Active contributors: {{repository.active_contributors}}

💡 *Next Steps*
{{#if issue.has_good_first_issue_label}}
This is a good first issue - ideal for new contributors! 🎉
{{/if}}
{{#if issue.has_bug_label}}
Bug triage team should review within 48 hours. 🐛
{{/if}}
{{#if issue.has_feature_label}}
Feature request posted to discussions. 🚀
{{/if}}
{{#if issue.has_documentation_label}}
Docs team review needed. 📚
{{/if}}

{{repository.owner.login}} team, please review and take action as appropriate.