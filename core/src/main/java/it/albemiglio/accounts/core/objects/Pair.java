package it.albemiglio.accounts.core.objects;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class Pair<L, R> {

    private L left;
    private R right;

    public static <L, R> Pair<L, R> of(L left, R right) {
        return new Pair<>(left, right);
    }

    public Pair<R, L> reverse() {
        return new Pair<>(right, left);
    }
}
