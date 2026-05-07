#include <iostream>

int main() {
    int n;
    std::cin >> n;
 
    // use long long because of the fat size fib numbers get up to
    long long previous = 0;
    long long current = 1;
    for (int i = 0; i < n; i++) {
        long long next = previous + current;
        previous = current;
        current = next;
    }

    std::cout << previous << '\n';
    return 0;
}
