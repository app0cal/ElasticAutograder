def fib(n):
    # standard iterative dp approach with O(1) space complexity
    current, next_value = 0, 1
    for _ in range(n):
        current, next_value = next_value, current + next_value
    return current
