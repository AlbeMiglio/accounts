package it.albemiglio.accounts.core.objects;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class Pair<L, R> {

    private L left;
    private R right;
}
