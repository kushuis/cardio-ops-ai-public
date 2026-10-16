-- Add immutable device-profile and knowledge-revision snapshots.
ALTER TABLE agent_policy_snapshot
    ADD COLUMN IF NOT EXISTS device_profile_version VARCHAR(64) NOT NULL DEFAULT 'device-profiles-v1';
ALTER TABLE agent_policy_snapshot
    ADD COLUMN IF NOT EXISTS knowledge_revisions_json TEXT NOT NULL
        DEFAULT '["scheduled-miss-service-playbook@1.0.0","icm-v1-scheduled-miss-trigger-guide@1.0.0","icm-v2-scheduled-miss-trigger-guide@1.0.0"]';

-- Historical snapshots without revisions are mapped to the first reviewed baseline,
-- 避免旧 Checkpoint 恢复时退化成无 revision 的全库检索。
UPDATE agent_policy_snapshot
SET knowledge_revisions_json = '["scheduled-miss-service-playbook@1.0.0","icm-v1-scheduled-miss-trigger-guide@1.0.0","icm-v2-scheduled-miss-trigger-guide@1.0.0"]'
WHERE knowledge_revisions_json = '[]';
