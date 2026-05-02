What is the purpose of a manifest?

Three core parts (in the current implementation)

entry_function: name or identifier for a specific grading function

comparisons: 
- exact : when a list is returned this MUST follow the exact order as answer key
- unordered_exact : can be returned in any order for the answer key

tests_cases: a list of the actual test cases with arguments being passed in (currently doesn't support certian problems like trees )