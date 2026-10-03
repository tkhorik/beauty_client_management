-- Apply before deploying the language-aware backend. Existing preferences follow the device.
BEGIN;
ALTER TABLE users ADD COLUMN language_preference VARCHAR(16) NOT NULL DEFAULT 'system';
ALTER TABLE users ADD COLUMN language_revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE users ADD CONSTRAINT users_language_preference_check
    CHECK (language_preference IN ('system', 'en', 'ru'));
ALTER TABLE users ADD CONSTRAINT users_language_revision_check CHECK (language_revision >= 0);
COMMIT;
