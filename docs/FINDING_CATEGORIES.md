# Adding a Finding Category

Finding categories are strictly enforced across the Gemini structured schema, prompt instructions, and backend validator.

## Files to Update

1. **`GeminiCodeReviewProvider.java`** (`backend/src/main/java/com/codepilot/ai/GeminiCodeReviewProvider.java`):
   Add the new uppercase enum value to `FINDING_RESPONSE_SCHEMA` under `properties.category.enum`.
2. **`ReviewResponseValidator.java`** (`backend/src/main/java/com/codepilot/ai/ReviewResponseValidator.java`):
   Add the same uppercase string to `VALID_CATEGORIES`.
3. **`ReviewPromptBuilder.java`** (`backend/src/main/java/com/codepilot/ai/ReviewPromptBuilder.java`):
   Update the system instruction text so Gemini knows when to select the new category.
4. **Tests**:
   Add a test case in `backend/src/test/java/com/codepilot/ai/ReviewResponseValidatorTest.java` verifying both uppercase and mixed-case normalization for the new category.
